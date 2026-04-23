/*
 * Copyright 2026 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.ai.edge.gallery.data

import android.content.Context
import android.util.Log
import java.io.File

private const val TAG = "NativeShellBridge"

/**
 * Execution engine that runs PRoot + Bash using the **"native shared library" smuggling**
 * technique for Android SDK 34+ W^X (Write XOR Execute) compatibility.
 *
 * ## The Problem
 * Android 10+ (API 29) and tightened further at targetSdk ≥ 29 enforces W^X: files
 * written to app-private storage (e.g. `filesDir`) are writable but not executable.
 * This prevents extracting and directly executing binary tools such as `bash` or
 * `proot` the way the bootstrap installer does at targetSdk 28.
 *
 * ## The Bypass
 * Files placed in the app's `jniLibs/` source folder at compile time are installed by
 * the Android package manager into `applicationInfo.nativeLibraryDir` **with execute
 * permission** — because the system treats them as native shared libraries that must be
 * loadable by the dynamic linker.  Any file in this directory can therefore be executed
 * by a [ProcessBuilder], even at targetSdk 34+.
 *
 * By renaming:
 * - `proot`  → `libproot.so`   (placed in `src/main/jniLibs/<abi>/libproot.so`)
 * - `bash`   → `libbash.so`    (placed in `src/main/jniLibs/<abi>/libbash.so`)
 *
 * …both binaries inherit execute permission from the package installer and can be
 * launched via [ProcessBuilder] without root, SELinux denial, or `chmod`.
 *
 * ## PRoot Jail Layout
 * ```
 * libproot.so  -r <workspace>  -0  -b /dev  -b /proc  -b /sys  -w /  libbash.so  --login
 * │             │               │   └─────────────────────────────┘    │            │
 * │             │               │          bind-mount pseudo-fs         │            └── login shell
 * │             │               └── fake uid=0 (no real root needed)    └── bash inside jail
 * │             └── chroot root = internal workspace dir
 * └── proot binary (disguised as .so)
 * ```
 *
 * ## Setup
 * 1. Obtain pre-compiled `proot` and `bash` binaries for your target ABIs.
 *    Recommended source: https://github.com/termux/proot-distro or UserLAnd.
 * 2. Rename them to `libproot.so` / `libbash.so`.
 * 3. Copy to `app/src/main/jniLibs/<abi>/` (e.g. `arm64-v8a`, `x86_64`).
 * 4. The Gradle build will pick them up automatically — no CMake required.
 * 5. On device: check [isAvailable] before using this bridge.
 *
 * @see TermuxSessionBridge for the fallback PTY-based execution engine.
 */
class NativeShellBridge(private val context: Context) {

  /**
   * The directory where the package manager extracted all `.so` files during
   * installation.  Files here are guaranteed to have execute permission.
   */
  val nativeLibraryDir: String = context.applicationInfo.nativeLibraryDir

  /** The PRoot binary, packaged as `libproot.so` to gain install-time execute permission. */
  val prootBin: File get() = File(nativeLibraryDir, "libproot.so")

  /** The Bash binary, packaged as `libbash.so` to gain install-time execute permission. */
  val bashBin: File get() = File(nativeLibraryDir, "libbash.so")

  /**
   * `true` when both [prootBin] and [bashBin] are present and executable.
   *
   * Check this before using [buildCommand] or [buildProcessBuilder]; if `false`,
   * fall back to [TermuxSessionBridge] or the bootstrap-installed bash path.
   */
  val isAvailable: Boolean
    get() {
      val prootOk = prootBin.exists() && prootBin.canExecute()
      val bashOk = bashBin.exists() && bashBin.canExecute()
      if (!prootOk) Log.w(TAG, "proot not available at ${prootBin.absolutePath}")
      if (!bashOk) Log.w(TAG, "bash not available at ${bashBin.absolutePath}")
      return prootOk && bashOk
    }

  /**
   * Returns the full argument list for launching Bash inside a PRoot jail.
   *
   * The jail isolates the session inside [workspace] while bind-mounting the
   * essential pseudo-filesystems so that standard Linux programs behave correctly:
   * - `/dev`  — character devices, TTY, random, null, etc.
   * - `/proc` — process info required by many programs (e.g. `ps`, `top`, `env`)
   * - `/sys`  — kernel tunables; some tools read it even when they don't need it
   *
   * @param workspace Root directory of the PRoot jail.  Must exist before calling this.
   * @return Ordered list of tokens for [ProcessBuilder] or [Runtime.exec].
   */
  fun buildCommand(workspace: File): List<String> = buildList {
    add(prootBin.absolutePath)      // proot binary (disguised as .so)

    // ── Jail flags ──────────────────────────────────────────────────────
    add("-r"); add(workspace.absolutePath)   // chroot-like root
    add("-0")                                // fake uid=0; no real root required
    add("-b"); add("/dev")                   // bind-mount /dev
    add("-b"); add("/proc")                  // bind-mount /proc
    add("-b"); add("/sys")                   // bind-mount /sys
    add("-w"); add("/")                      // initial working directory inside jail

    // ── Shell ────────────────────────────────────────────────────────────
    add(bashBin.absolutePath)               // bash (disguised as .so)
    add("--login")                           // source /etc/profile & ~/.bash_profile
  }

  /**
   * Builds a [ProcessBuilder] fully configured for the PRoot jail.
   *
   * The environment is set so that:
   * - [nativeLibraryDir] is on `LD_LIBRARY_PATH` — proot's own embedded shared libs
   *   (if any) are resolved correctly.
   * - Standard POSIX variables (`HOME`, `TERM`, `LANG`) are populated so interactive
   *   programs behave as expected.
   * - PRoot control variables suppress problematic seccomp filters and set a temp dir.
   *
   * The builder has `redirectErrorStream = true` so stderr merges into stdout —
   * both streams are visible in the terminal and in the Logcat overlay.
   *
   * @param workspace Root of the PRoot jail and [ProcessBuilder.directory].
   * @return Configured [ProcessBuilder] ready for [ProcessBuilder.start].
   */
  fun buildProcessBuilder(workspace: File): ProcessBuilder {
    val cmd = buildCommand(workspace)
    Log.d(TAG, "NativeShellBridge command: ${cmd.joinToString(" ")}")

    return ProcessBuilder(cmd)
      .directory(workspace)
      .redirectErrorStream(true)
      .also { pb ->
        val env = pb.environment()
        // Allow proot's embedded native libs to be found by the dynamic linker.
        env["LD_LIBRARY_PATH"] = nativeLibraryDir
        // PATH: nativeLibraryDir first so libproot.so / libbash.so are on PATH
        //       as well as the standard Android bin dirs.
        env["PATH"] = "$nativeLibraryDir:/system/bin:/system/xbin"
        // POSIX environment inside the jail.
        env["HOME"] = "/"
        env["TERM"] = "xterm-256color"
        env["LANG"] = "en_US.UTF-8"
        // PRoot diagnostics and compatibility flags.
        env["PROOT_NO_SECCOMP"] = "1"         // disable seccomp shim (problematic on some kernels)
        env["PROOT_NO_SYSVIPC"] = "1"         // skip SysV IPC emulation
        env["PROOT_VERBOSE"] = "5"             // log level 5 — visible in Logcat
        env["PROOT_TMP_DIR"] = "${workspace.absolutePath}/tmp"
      }
  }

  companion object {
    /**
     * Returns a multi-line diagnostic string for logging in the Logcat overlay.
     *
     * Reports binary availability, paths, and execute permissions so that missing
     * `.so` files are easy to identify during development.
     *
     * Example output:
     * ```
     * === NativeShellBridge Diagnostic ===
     * nativeLibraryDir: /data/app/.../lib/arm64
     * libproot.so : exists=true  canExecute=true
     * libbash.so  : exists=false canExecute=false   ← binary not yet placed in jniLibs
     * isAvailable : false
     * === End Diagnostic ===
     * ```
     */
    fun diagnose(context: Context): String {
      val bridge = NativeShellBridge(context)
      return buildString {
        appendLine("=== NativeShellBridge Diagnostic ===")
        appendLine("nativeLibraryDir : ${bridge.nativeLibraryDir}")
        appendLine(
          "libproot.so      : exists=${bridge.prootBin.exists()}" +
            "  canExecute=${bridge.prootBin.canExecute()}" +
            "  path=${bridge.prootBin.absolutePath}",
        )
        appendLine(
          "libbash.so       : exists=${bridge.bashBin.exists()}" +
            "  canExecute=${bridge.bashBin.canExecute()}" +
            "  path=${bridge.bashBin.absolutePath}",
        )
        appendLine("isAvailable      : ${bridge.isAvailable}")
        appendLine("=== End Diagnostic ===")
      }
    }
  }
}
