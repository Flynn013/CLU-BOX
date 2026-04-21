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
import android.os.Build
import android.system.Os
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

private const val TAG = "EnvironmentInstaller"

/**
 * Bootstraps a self-contained Linux sysroot inside the app's private data
 * directory by extracting the Termux `bootstrap-aarch64.zip`.
 *
 * **Source priority:**
 * 1. Bundled asset at `assets/bootstrap-aarch64.zip` — used when the zip is
 *    shipped inside the APK (avoids any network requirement).
 * 2. Network download from the official Termux release — used as a fallback
 *    when no bundled asset is present.
 *
 * After installation the sysroot lives directly inside `context.filesDir/`
 * (i.e. `filesDir/bin/bash`, `filesDir/lib/`, …) and provides `bash`,
 * `apt`/`pkg`, and the corresponding shared libraries — all without requiring
 * the external Termux app to be installed.
 *
 * Typical lifecycle:
 * 1. [MstrCtrlScreen] or [TerminalSessionManager] calls [ensureInstalled].
 * 2. If the sysroot is already present the call is a no-op.
 * 3. Otherwise the bootstrap archive is copied from assets (or downloaded),
 *    extracted, symlinks are created from `SYMLINKS.txt`, and file permissions
 *    are fixed.
 * 4. Once [state] emits [State.Ready], the terminal can start.
 */
object EnvironmentInstaller {

  /**
   * Determines the correct Termux bootstrap CPU architecture string for the
   * running device by inspecting [Build.SUPPORTED_ABIS] in priority order.
   *
   * Mapping:
   * - `arm64-v8a`  → `aarch64`  (64-bit ARM — all modern phones)
   * - `armeabi-v7a`→ `arm`       (32-bit ARM — older phones)
   * - `x86_64`     → `x86_64`   (64-bit x86 — emulators, ChromeOS)
   * - `x86`        → `i686`     (32-bit x86 — old emulators)
   *
   * Falls back to `aarch64` when no match is found.
   */
  private val bootstrapArch: String = when {
    Build.SUPPORTED_ABIS.contains("arm64-v8a")   -> "aarch64"
    Build.SUPPORTED_ABIS.contains("armeabi-v7a") -> "arm"
    Build.SUPPORTED_ABIS.contains("x86_64")      -> "x86_64"
    Build.SUPPORTED_ABIS.contains("x86")         -> "i686"
    else                                          -> "aarch64"
  }

  /**
   * Name of the bootstrap asset bundled in `app/src/main/assets/`.
   * File must match the running device's CPU architecture.
   */
  private val BOOTSTRAP_ASSET_NAME: String = "bootstrap-$bootstrapArch.zip"

  /**
   * Name of the `proot` binary bundled in `app/src/main/assets/`.
   *
   * proot intercepts filesystem syscalls so the app's sysroot can be
   * bind-mounted to the hardcoded Termux prefix expected by `apt`, `dpkg`,
   * and every shell script with a `/data/data/com.termux/…` shebang — all
   * without root privileges.
   */
  private const val PROOT_ASSET_NAME = "proot"

  /**
   * Primary download URL — official Termux bootstrap for the device arch.
   * Uses the GitHub `latest` redirect so it always points to the newest release.
   */
  private val BOOTSTRAP_URL: String =
    "https://github.com/termux/termux-packages/releases/latest/download/bootstrap-$bootstrapArch.zip"

  /**
   * Fallback download URL using a known-stable Termux release tag.
   * Used when the `latest` redirect fails or returns a non-200 response.
   * Update this tag when a newer stable bootstrap is published.
   */
  private val BOOTSTRAP_URL_FALLBACK: String =
    "https://github.com/termux/termux-packages/releases/download/bootstrap-2024.12.16/bootstrap-$bootstrapArch.zip"

  /** SharedPreferences file used to track installation state across launches. */
  private const val PREFS_NAME = "env_installer_prefs"
  private const val KEY_INSTALLED = "bootstrap_installed"
  /** Persisted once script shebang paths have been rewritten for this app's prefix. */
  private const val KEY_SHEBANGS_PATCHED = "shebangs_patched"
  /**
   * Stores the prefix path that was active when shebangs were last patched.
   * If it differs from the current [prefixDir], shebangs are re-patched so that
   * a change from the old `filesDir/usr` layout to the new flat `filesDir` layout
   * is automatically healed on the next boot.
   */
  private const val KEY_SHEBANGS_PATCHED_PREFIX = "shebangs_patched_prefix"

  /**
   * Hard-coded sysroot prefix baked into every Termux bootstrap script shebang,
   * e.g. `#!/data/data/com.termux/files/usr/bin/bash`.
   *
   * Must be replaced with the actual [prefixDir] after extraction so that `pkg`,
   * `apt-get`, and other shell scripts can be executed from our app's private
   * storage. Without this replacement the kernel cannot find the interpreter and
   * reports "inaccessible or not found" for every Termux script.
   */
  private const val TERMUX_HARDCODED_PREFIX = "/data/data/com.termux/files/usr"

  /**
   * Separator used in Termux's `SYMLINKS.txt` — the UTF-8 leftward arrow (U+2190).
   *
   * Each line has the format: `link_target←link_name`.
   */
  private const val SYMLINK_SEPARATOR = "←"

  // ── Observable installation state ──────────────────────────────────

  /** Describes the current phase of the bootstrap installation. */
  sealed class State {
    /** No installation attempted yet. */
    data object Idle : State()

    /** Downloading the bootstrap archive. [percent] is 0–100. */
    data class Downloading(val percent: Int) : State()

    /** Extracting the zip to the sysroot. */
    data object Extracting : State()

    /** Processing symlinks and fixing file permissions. */
    data object FixingPermissions : State()

    /** The sysroot is fully installed and ready to use. */
    data object Ready : State()

    /** Installation failed. [message] contains a human-readable reason. */
    data class Failed(val message: String) : State()
  }

  private val _state = MutableStateFlow<State>(State.Idle)

  /** Observable installation state for UI progress indicators. */
  val state: StateFlow<State> = _state.asStateFlow()

  /**
   * Guards concurrent calls to [ensureInstalled].
   *
   * Without this mutex, two simultaneous callers (e.g. a UI LaunchedEffect
   * and TerminalSessionManager.runPreFlightCheck) both pass the
   * `isInstalled()` fast-path check, both download to the same
   * `cacheDir/bootstrap-aarch64.zip` file concurrently, and the two
   * interleaved writes corrupt the archive — causing extraction to fail
   * with an invalid-zip error and leaving the environment permanently broken.
   */
  private val installMutex = Mutex()

  // ── Path helpers ───────────────────────────────────────────────────

  /**
   * Root of the internal sysroot: `context.filesDir` directly.
   *
   * The Termux bootstrap archive uses a flat layout — entries like `bin/bash`
   * extract directly into `filesDir/bin/bash` rather than the `usr/`-prefixed
   * layout used by the official Termux app.  `PREFIX` therefore equals
   * `filesDir` itself.
   */
  fun prefixDir(context: Context): File = context.filesDir

  /** Bin directory containing executables (`$PREFIX/bin`). */
  fun binDir(context: Context): File = File(prefixDir(context), "bin")

  /** Lib directory containing shared libraries (`$PREFIX/lib`). */
  fun libDir(context: Context): File = File(prefixDir(context), "lib")

  /**
   * The `$HOME` directory for Termux sessions: `context.filesDir/clu_file_box`.
   *
   * This is the shared sandbox root used by both the PTY terminal and the AI
   * file-box workspace, matching [TerminalSessionManager.sandboxRoot].
   */
  fun homeDir(context: Context): File = File(context.filesDir, "clu_file_box")

  /**
   * The `$TMPDIR` for Termux sessions: `$PREFIX/tmp`.
   *
   * Placing tmp inside the sysroot ensures that temporary files created by
   * package management scripts (e.g. `pkg`/`apt`) stay within a directory
   * that bash and libc can find via the standard Termux conventions.
   */
  fun tmpDir(context: Context): File = File(prefixDir(context), "tmp")

  /** Absolute path to the internal `bash` binary. */
  fun bashPath(context: Context): File = File(binDir(context), "bash")

  /** Absolute path to the internal `sh` binary. */
  fun shPath(context: Context): File = File(binDir(context), "sh")

  /** Absolute path to the internal `proot` binary. */
  fun prootPath(context: Context): File = File(binDir(context), "proot")

  /**
   * Returns the best available shell path inside the bootstrapped sysroot.
   *
   * Resolution order:
   * 1. `$PREFIX/bin/bash` — full Bash shell (preferred).
   * 2. `$PREFIX/bin/sh` — POSIX shell fallback.
   * 3. `/system/bin/sh` — stock Android shell (pre-bootstrap or if bootstrap failed).
   */
  fun shellPath(context: Context): String {
    val bash = bashPath(context)
    if (bash.exists() && bash.canExecute()) return bash.absolutePath
    val sh = shPath(context)
    if (sh.exists() && sh.canExecute()) return sh.absolutePath
    return "/system/bin/sh"
  }

  /**
   * Builds the command list used to launch a shell process.
   *
   * When [prootPath] exists and is executable **and** `bash` is also available,
   * the returned list wraps bash with proot so that syscall interception binds
   * `filesDir` to `/data/data/com.termux/files/usr`. This allows `apt`, `dpkg`,
   * and every shell script with a Termux shebang to run natively without any
   * string-replacement patching.
   *
   * When proot is absent (not bundled or not yet installed) the list falls back
   * to `[shellPath, *shellArgs]`, preserving the existing direct-exec behaviour.
   *
   * @param shellArgs Extra arguments forwarded to the shell, e.g. `"--login"`,
   *                  or `"-c"` followed by a command string for non-interactive use.
   * @return A non-empty list whose first element is the executable to launch.
   */
  fun buildShellCommand(context: Context, shellArgs: Array<String> = emptyArray()): List<String> {
    val proot = prootPath(context)
    val bash  = bashPath(context)
    if (proot.exists() && proot.canExecute() && bash.exists() && bash.canExecute()) {
      return buildList {
        add(proot.absolutePath)
        add("-0")                     // fake root — bypasses UID checks in apt/dpkg
        add("-b")
        add("${prefixDir(context).absolutePath}:$TERMUX_HARDCODED_PREFIX")
        add("-b"); add("/dev")        // device nodes required by PTY
        add("-b"); add("/proc")       // process info required by various tools
        add("-w"); add(homeDir(context).absolutePath) // initial working directory
        add(bash.absolutePath)
        addAll(shellArgs)
      }
    }
    // proot not available — launch shell directly (shebang-patching is the fallback).
    val shell = shellPath(context)
    return buildList {
      add(shell)
      addAll(shellArgs)
    }
  }

  /**
   * Returns `true` when the sysroot appears to be fully installed.
   *
   * Checks the persisted flag first (fast), then verifies that the key
   * binary actually exists on disk (correctness).
   */
  fun isInstalled(context: Context): Boolean {
    val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    if (!prefs.getBoolean(KEY_INSTALLED, false)) return false
    return binDir(context).isDirectory &&
      (bashPath(context).exists() || shPath(context).exists())
  }

  /**
   * Returns `true` when the hardcoded Termux shebang paths inside the sysroot
   * have already been rewritten to this app's current [prefixDir].
   *
   * Returns `false` if either the flag was never set OR the prefix stored at
   * patch time differs from the current [prefixDir] (handles upgrade from the
   * old `filesDir/usr` layout to the new flat `filesDir` layout).
   */
  fun isPatched(context: Context): Boolean {
    val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    if (!prefs.getBoolean(KEY_SHEBANGS_PATCHED, false)) return false
    val patchedPrefix = prefs.getString(KEY_SHEBANGS_PATCHED_PREFIX, "") ?: ""
    return patchedPrefix == prefixDir(context).absolutePath
  }

  // ── Public API ─────────────────────────────────────────────────────

  /**
   * Clears all installation flags and re-runs the full bootstrap installation.
   *
   * Safe to call from the UI (e.g. from a "Retry" button in
   * [BootstrapProgressOverlay]) when a previous [ensureInstalled] call failed.
   * Deletes any partially-extracted sysroot so the extraction starts clean.
   */
  suspend fun retry(context: Context) {
    Log.i(TAG, "retry: clearing installation state and re-bootstrapping")
    // Reset observable state first so the UI immediately shows the overlay.
    _state.value = State.Idle

    // Clear all installation flags so ensureInstalled runs the full path.
    context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
      .edit()
      .putBoolean(KEY_INSTALLED, false)
      .putBoolean(KEY_SHEBANGS_PATCHED, false)
      .remove(KEY_SHEBANGS_PATCHED_PREFIX)
      .apply()

    // Delete the partially-extracted sysroot (if any) to start clean.
    withContext(Dispatchers.IO) {
      prefixDir(context).deleteRecursively()
    }

    ensureInstalled(context)
  }

  /**
   * Ensures the Linux sysroot is installed, executable, and shebang-patched.
   *
   * **Three work tiers handled in a single mutex-protected block:**
   * 1. **Full install** — bootstrap archive is missing entirely: download (or copy
   *    from bundled asset) and extract.
   * 2. **Permission repair** — sysroot is present but bash lost its execute bit
   *    (e.g. after an OTA update or storage-encryption re-key): re-run
   *    [fixPermissions].
   * 3. **Shebang patching** — script shebangs still contain the hardcoded Termux
   *    prefix (`/data/data/com.termux/files/usr`): replace with this app's actual
   *    [prefixDir] so `pkg`, `apt-get`, and every other shell script can be
   *    exec'd directly.
   *
   * Must be called from a coroutine context. Heavy I/O runs on [Dispatchers.IO].
   */
  suspend fun ensureInstalled(context: Context) {
    // Truly fast path: installed, executable, and already patched — nothing to do.
    if (isInstalled(context) && isPatched(context) &&
        (bashPath(context).canExecute() || shPath(context).canExecute())) {
      _state.value = State.Ready
      return
    }

    // Need to do some work. Acquire the mutex so that only one coroutine proceeds
    // at a time (prevents concurrent download/extract races and double-patching).
    installMutex.withLock {
      // Double-check after acquiring the lock.
      if (isInstalled(context) && isPatched(context) &&
          (bashPath(context).canExecute() || shPath(context).canExecute())) {
        _state.value = State.Ready
        return
      }

      withContext(Dispatchers.IO) {
        try {
          // ── Tier 1: Full install if the sysroot is missing ───────────────
          if (!isInstalled(context)) {
            val zipFile = File(context.cacheDir, BOOTSTRAP_ASSET_NAME)

            // Prefer bundled asset; fall back to network download.
            val fromAsset = tryCopyFromAsset(context, zipFile)
            if (!fromAsset) {
              _state.value = State.Downloading(0)
              downloadBootstrap(zipFile)
            }

            _state.value = State.Extracting
            extractBootstrap(zipFile, context.filesDir)

            _state.value = State.FixingPermissions
            processSymlinks(context.filesDir)
            fixPermissions(context)

            // Create runtime directories that Termux expects.
            homeDir(context).mkdirs()
            tmpDir(context).mkdirs()

            zipFile.delete()

            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
              .edit().putBoolean(KEY_INSTALLED, true).apply()

            Log.i(TAG, "Bootstrap extracted to ${prefixDir(context).absolutePath}")
          }

          // ── Tier 2: Permission repair ────────────────────────────────────
          // The zip format does not preserve Unix permissions and Android OTA
          // updates can strip execute bits. Re-apply 0755 whenever bash is
          // not directly executable; this also covers first-run-after-extract.
          if (!bashPath(context).canExecute() && !shPath(context).canExecute()) {
            Log.i(TAG, "Repairing execute permissions on sysroot binaries")
            _state.value = State.FixingPermissions
            fixPermissions(context)
          }

          // ── Tier 3: Shebang path patching ────────────────────────────────
          // Every Termux bootstrap script has `#!/data/data/com.termux/files/usr/…`
          // hard-coded as its interpreter path.  Kernel exec fails with "No such
          // file or directory" until we rewrite these to this app's actual prefix.
          // isPatched() also returns false when the stored prefix differs from the
          // current prefixDir (handles upgrade from filesDir/usr → filesDir).
          if (!isPatched(context)) {
            Log.i(TAG, "Patching Termux shebang paths → ${prefixDir(context).absolutePath}")
            _state.value = State.FixingPermissions
            patchShebangPaths(context)
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
              .edit()
              .putBoolean(KEY_SHEBANGS_PATCHED, true)
              .putString(KEY_SHEBANGS_PATCHED_PREFIX, prefixDir(context).absolutePath)
              .apply()
          }

          // ── Tier 4: proot installation ───────────────────────────────────
          // Copy proot from assets into bin/ so syscall interception is
          // available on the next shell launch. installProot is idempotent.
          installProot(context)

          _state.value = State.Ready
          Log.i(TAG, "Bootstrap ready at ${prefixDir(context).absolutePath}")
        } catch (e: Exception) {
          Log.e(TAG, "Bootstrap installation failed", e)
          _state.value = State.Failed(e.message ?: "Unknown error")
        }
      } // withContext(Dispatchers.IO)
    } // installMutex.withLock
  }

  // ── Private helpers ────────────────────────────────────────────────

  /**
   * Attempts to copy the bundled `assets/bootstrap-aarch64.zip` to [destination].
   *
   * @return `true` if the asset was found and successfully copied, `false` if
   *         the asset is not bundled (caller should fall back to downloading).
   */
  private fun tryCopyFromAsset(context: Context, destination: File): Boolean {
    return try {
      context.assets.open(BOOTSTRAP_ASSET_NAME).use { input ->
        Log.d(TAG, "Copying bootstrap from bundled asset")
        FileOutputStream(destination).use { output ->
          input.copyTo(output)
        }
      }
      Log.d(TAG, "Asset copy complete → ${destination.absolutePath}")
      true
    } catch (e: IOException) {
      Log.d(TAG, "No bundled bootstrap asset found ($BOOTSTRAP_ASSET_NAME) — will download")
      false
    }
  }

  /**
   * Downloads the bootstrap archive to [destination], trying the primary URL
   * first and the [BOOTSTRAP_URL_FALLBACK] if the primary returns a non-200
   * response or throws a network error.
   *
   * Follows HTTP redirects (up to 5 per attempt) and sends a User-Agent that
   * identifies the request so GitHub/CDN does not rate-limit anonymous traffic.
   */
  private fun downloadBootstrap(destination: File) {
    val urls = listOf(BOOTSTRAP_URL, BOOTSTRAP_URL_FALLBACK)
    var lastException: Exception? = null

    for ((index, url) in urls.withIndex()) {
      try {
        Log.d(TAG, "downloadBootstrap: attempt ${index + 1} from $url")
        downloadFromUrl(url, destination)
        Log.i(TAG, "downloadBootstrap: succeeded from $url")
        return // success — no need to try fallback
      } catch (e: Exception) {
        Log.w(TAG, "downloadBootstrap: attempt ${index + 1} failed (${e.message})")
        lastException = e
        destination.delete() // clean up partial file before retry
      }
    }

    throw lastException ?: RuntimeException("All download URLs exhausted")
  }

  /**
   * Downloads from a single [urlString], following up to 5 HTTP redirects.
   * Sets a User-Agent so GitHub CDN does not treat the request as a bot.
   */
  private fun downloadFromUrl(urlString: String, destination: File) {
    var currentUrl = URL(urlString)
    var redirectCount = 0
    var connection: HttpURLConnection

    while (true) {
      if (redirectCount > 5) throw RuntimeException("Too many redirects ($redirectCount)")
      Log.d(TAG, "downloadFromUrl: connecting to $currentUrl")
      connection = currentUrl.openConnection() as HttpURLConnection
      connection.instanceFollowRedirects = false
      connection.connectTimeout = 30_000
      connection.readTimeout = 60_000
      // Identify the app to GitHub/CDN. Using a fixed product token avoids
      // rate-limiting of anonymous requests; Build.VERSION.RELEASE and
      // Build.MODEL give enough context for debugging without hard-coding a
      // version number that would drift out of sync with BuildConfig.
      connection.setRequestProperty(
        "User-Agent",
        "CLU-BOX (Android ${Build.VERSION.RELEASE}; ${Build.MODEL}) TermuxBootstrapInstaller",
      )
      connection.connect()

      val responseCode = connection.responseCode
      Log.d(TAG, "downloadFromUrl: HTTP $responseCode from $currentUrl")

      when (responseCode) {
        HttpURLConnection.HTTP_MOVED_TEMP,
        HttpURLConnection.HTTP_MOVED_PERM,
        HttpURLConnection.HTTP_SEE_OTHER,
        307, 308,
        -> {
          val location = connection.getHeaderField("Location")
            ?: throw RuntimeException("Redirect without Location header")
          connection.disconnect()
          Log.d(TAG, "downloadFromUrl: redirect #$redirectCount → $location")
          currentUrl = URL(location)
          redirectCount++
        }

        HttpURLConnection.HTTP_OK -> {
          Log.d(TAG, "downloadFromUrl: stream ready (${connection.contentLengthLong} bytes)")
          break
        }

        else -> {
          connection.disconnect()
          throw RuntimeException("Download failed: HTTP $responseCode from $currentUrl")
        }
      }
    }

    try {
      val totalBytes = connection.contentLengthLong
      var downloadedBytes = 0L

      BufferedInputStream(connection.inputStream).use { input ->
        FileOutputStream(destination).use { output ->
          val buffer = ByteArray(8192)
          var bytesRead: Int
          while (input.read(buffer).also { bytesRead = it } != -1) {
            output.write(buffer, 0, bytesRead)
            downloadedBytes += bytesRead
            if (totalBytes > 0) {
              val percent = ((downloadedBytes * 100) / totalBytes).toInt()
              _state.value = State.Downloading(percent.coerceIn(0, 100))
            }
          }
        }
      }

      Log.d(TAG, "downloadFromUrl: downloaded $downloadedBytes bytes")
    } finally {
      connection.disconnect()
    }
  }

  /**
   * Extracts the bootstrap zip into [targetDir].
   *
   * The zip uses a flat layout — entries like `bin/bash`, `lib/libc.so`,
   * etc. sit directly at the archive root, so extraction to `context.filesDir`
   * produces `context.filesDir/bin/bash`.
   */
  private fun extractBootstrap(zipFile: File, targetDir: File) {
    ZipInputStream(zipFile.inputStream().buffered()).use { zis ->
      var entry = zis.nextEntry
      while (entry != null) {
        val outFile = File(targetDir, entry.name)
        // Guard against zip-slip path traversal attacks.
        val targetCanonical = targetDir.canonicalPath + File.separator
        if (!outFile.canonicalPath.startsWith(targetCanonical)) {
          throw SecurityException("Zip entry outside target dir: ${entry.name}")
        }
        if (entry.isDirectory) {
          outFile.mkdirs()
        } else {
          outFile.parentFile?.mkdirs()
          FileOutputStream(outFile).use { fos ->
            zis.copyTo(fos)
          }
        }
        zis.closeEntry()
        entry = zis.nextEntry
      }
    }
    Log.d(TAG, "Extracted bootstrap to ${targetDir.absolutePath}")
  }

  /**
   * Processes `SYMLINKS.txt` produced by the Termux bootstrap.
   *
   * Each line has the format `link_target←link_name` where both paths are
   * relative to [rootDir]. On Android API 21+ symlinks are created via
   * [android.system.Os.symlink].
   */
  private fun processSymlinks(rootDir: File) {
    val symlinksFile = File(rootDir, "SYMLINKS.txt")
    if (!symlinksFile.exists()) {
      Log.d(TAG, "No SYMLINKS.txt found — skipping symlink creation")
      return
    }

    var created = 0
    var failed = 0

    symlinksFile.readLines().forEach { line ->
      if (line.isBlank()) return@forEach
      val parts = line.split(SYMLINK_SEPARATOR)
      if (parts.size != 2) return@forEach

      val target = parts[0].trim()
      val linkPath = parts[1].trim()
      val linkFile = File(rootDir, linkPath)

      try {
        linkFile.parentFile?.mkdirs()
        // Remove any existing file at the link location.
        if (linkFile.exists()) linkFile.delete()
        Os.symlink(target, linkFile.absolutePath)
        created++
      } catch (e: Exception) {
        Log.w(TAG, "Symlink failed: $linkPath → $target (${e.message})")
        failed++
      }
    }

    // Clean up the SYMLINKS.txt to keep the sysroot tidy.
    symlinksFile.delete()
    Log.d(TAG, "Symlinks: $created created, $failed failed")
  }

  /**
   * Makes all files under `bin/`, `lib/`, and `libexec/` executable (0755).
   *
   * The bootstrap zip uses a flat layout (`filesDir/bin/`, `filesDir/lib/`, …).
   * The zip format does not preserve Unix permissions, so every binary and
   * shared library needs an explicit `chmod 0755` after extraction.
   *
   * Uses [Os.chmod] for world-executable permission setting to avoid Exit 126
   * ("Permission Denied") and dynamic-linker failures at runtime.
   */
  private fun fixPermissions(context: Context) {
    val dirs = listOf(
      binDir(context),
      libDir(context),
      File(prefixDir(context), "libexec"),
    )
    var count = 0
    for (dir in dirs) {
      if (!dir.isDirectory) continue
      dir.walkTopDown().filter { it.isFile }.forEach { file ->
        try {
          Os.chmod(file.absolutePath, 0b111_101_101) // 0755
          count++
        } catch (e: Exception) {
          // Fallback to Java API if Os.chmod fails (e.g. on older API levels).
          if (file.setExecutable(true, false)) count++
        }
      }
    }
    Log.d(TAG, "Made $count files executable")
  }

  /**
   * Rewrites hardcoded Termux shebang interpreter paths in shell scripts to use
   * this app's actual [prefixDir].
   *
   * Every script in `$PREFIX/bin/` and `$PREFIX/libexec/` extracted from the
   * official Termux bootstrap archive has a shebang like:
   * ```
   * #!/data/data/com.termux/files/usr/bin/bash
   * ```
   * When installed into *this* app's prefix the kernel cannot find the interpreter
   * at that path → "inaccessible or not found". Replacing every occurrence of the
   * hardcoded prefix with [prefixDir] (`filesDir`) makes `pkg`, `apt-get`,
   * `python3`, and all other Termux shell scripts directly executable under our
   * sysroot, e.g. `#!/data/user/0/<pkg>/files/bin/bash`.
   *
   * A second legacy prefix (`filesDir/usr`) is also replaced to handle devices
   * where an earlier code version incorrectly patched shebangs with a `usr/`-
   * appended prefix instead of the correct flat `filesDir` prefix.
   *
   * Additionally, Termux's runtime-patched `apt` and `dpkg` binaries respect the
   * `TERMUX_PREFIX` environment variable (set by [TermuxSessionBridge] and
   * [TerminalSessionManager]) to locate their config directories at runtime, so
   * package installation continues to work after the script shebangs are fixed.
   *
   * **Safety:** Only regular, non-symlink text files starting with `#!` are
   * rewritten. ELF binaries, symlinks, and non-UTF-8 files are skipped.
   * Symlinks are explicitly excluded — writing through a symlink would overwrite
   * the *target* binary (e.g. a busybox-applet symlink → corrupt busybox itself).
   */
  private fun patchShebangPaths(context: Context) {
    val newPrefix = prefixDir(context).absolutePath

    // Build list of stale prefixes to replace:
    // 1. Original Termux hardcode: /data/data/com.termux/files/usr
    // 2. Legacy wrong prefix: filesDir/usr — produced by an older version of
    //    this code that incorrectly set prefixDir = File(filesDir, "usr").
    val legacyWrongPrefix = File(newPrefix, "usr").absolutePath
    val oldPrefixes = listOf(TERMUX_HARDCODED_PREFIX, legacyWrongPrefix)

    var patched = 0

    val dirs = listOf(binDir(context), File(prefixDir(context), "libexec"))
    for (dir in dirs) {
      if (!dir.isDirectory) continue
      dir.walkTopDown().forEach { file ->
        if (!file.isFile) return@forEach
        // Skip symlinks — writing through them overwrites the symlink target,
        // e.g. an `ls -> busybox` applet symlink would corrupt the busybox binary.
        if (java.nio.file.Files.isSymbolicLink(file.toPath())) return@forEach
        try {
          // Only process shell scripts identified by their `#!` shebang header.
          val header = ByteArray(2)
          val bytesRead = file.inputStream().use { it.read(header) }
          if (bytesRead < 2 ||
              header[0] != '#'.code.toByte() ||
              header[1] != '!'.code.toByte()) {
            return@forEach // ELF binary or non-script file — leave untouched
          }
          val original = file.readText(Charsets.UTF_8)
          // Apply each replacement to the *running* string so that the output
          // of one replacement is the input of the next. Guard with != newPrefix
          // to ensure we never accidentally replace with an identical string.
          var content = original
          for (old in oldPrefixes) {
            if (old != newPrefix) {
              content = content.replace(old, newPrefix)
            }
          }
          if (content != original) {
            file.writeText(content, Charsets.UTF_8)
            file.setExecutable(true, false)
            patched++
          }
        } catch (e: Exception) {
          // Binary-looking file detected as text, encoding error, or permission
          // issue — skip silently.  Log at debug level to aid device diagnostics.
          Log.d(TAG, "patchShebangPaths: skipping ${file.name} (${e.javaClass.simpleName})")
        }
      }
    }

    Log.d(TAG, "patchShebangPaths: rewrote $patched scripts (→ $newPrefix)")
  }

  /**
   * Copies the bundled `proot` binary from `assets/` into `$PREFIX/bin/proot`
   * and marks it world-executable.
   *
   * proot uses `ptrace`-based syscall interception to bind-mount `filesDir` to
   * `/data/data/com.termux/files/usr` so that `apt`, `dpkg`, and shell scripts
   * with Termux shebangs run natively — no root required.
   *
   * This is a best-effort operation: if the asset is absent (developer chose not
   * to bundle proot) the function logs at debug level and returns silently.  The
   * engine then falls back to direct bash execution + shebang-patching.
   */
  private fun installProot(context: Context) {
    val dest = prootPath(context)
    if (dest.exists() && dest.canExecute()) return // already installed — fast path

    try {
      context.assets.open(PROOT_ASSET_NAME).use { input ->
        dest.parentFile?.mkdirs()
        FileOutputStream(dest).use { output -> input.copyTo(output) }
      }
      dest.setExecutable(true, true)  // owner-only — no need for world-exec
      Log.i(TAG, "installProot: proot installed at ${dest.absolutePath}")
    } catch (e: IOException) {
      // Asset not bundled — proot integration is disabled; shebang-patching is
      // the active fallback.  Not an error condition.
      Log.d(TAG, "installProot: '$PROOT_ASSET_NAME' asset not found — proot disabled")
    }
  }
}
