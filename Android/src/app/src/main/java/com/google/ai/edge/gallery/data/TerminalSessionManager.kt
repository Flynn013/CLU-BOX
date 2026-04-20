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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

private const val TAG = "TerminalSessionManager"

/** Preferences key to detect first-ever boot. */
private const val PREFS_NAME = "mstr_ctrl_prefs"
private const val KEY_FIRST_BOOT_DONE = "first_boot_done"

/** Timeout for individual commands piped through the session (seconds). */
private const val CMD_TIMEOUT_SECONDS = 10L

/** Maximum time (ms) to wait for reader threads to finish after the process exits. */
private const val READER_THREAD_JOIN_TIMEOUT_MS = 5_000L

/** Maximum number of lines retained in the visible output buffer to limit GC pressure. */
private const val MAX_OUTPUT_LINES = 2000

/**
 * Represents a single line of terminal output, tagged by source.
 */
data class TerminalLine(
  val text: String,
  val source: LineSource,
)

enum class LineSource { STDIN, STDOUT, STDERR, SYSTEM }

/**
 * Manages a persistent interactive shell session backed by `sh`.
 *
 * The session's `$HOME` and initial working directory are set to the
 * [FileBoxManager.root] (`clu_file_box`) sandbox. The session survives
 * across screen rotations and navigation events as long as the hosting
 * [Context] (Application) is alive.
 *
 * On first boot, an initialization hook triggers [EnvironmentInstaller] to
 * download and install the Termux bootstrap sysroot (bash, pkg, python, etc.)
 * into the app's internal storage. Once installed, subsequent boots skip the
 * download and use the sysroot directly.
 *
 * The Termux `terminal-emulator` / `terminal-view` libraries are included as
 * JitPack dependencies, providing PTY-based terminal sessions and a native
 * [TerminalView] widget. See [TermuxSessionBridge] for the PTY integration.
 */
class TerminalSessionManager(private val context: Context) {

  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

  /** The clu_file_box root directory used as $HOME and cwd. */
  val sandboxRoot: File by lazy {
    File(context.filesDir, "clu_file_box").also { it.mkdirs() }
  }

  // ── Observable terminal output buffer ────────────────────────
  private val _outputLines = MutableStateFlow<List<TerminalLine>>(emptyList())
  val outputLines: StateFlow<List<TerminalLine>> = _outputLines.asStateFlow()

  // ── Persistent shell process ─────────────────────────────────
  /** Guards all mutable session state (process, stdinWriter, isSessionAlive). */
  private val sessionLock = ReentrantLock()

  private var process: Process? = null
  private var stdinWriter: OutputStreamWriter? = null
  @Volatile private var isSessionAlive = false

  /** True once the first-boot firmware hook has completed. */
  private var firstBootHandled = false

  // ── Pre-flight readiness gate ───────────────────────────────
  // The AI must not initialize until runPreFlightCheck() succeeds.
  private val _preFlightReady = MutableStateFlow(false)

  /** Emits `true` once the pre-flight bootstrap completes with exit code 0. */
  val preFlightReady: StateFlow<Boolean> = _preFlightReady.asStateFlow()

  // ── Public API ───────────────────────────────────────────────

  /**
   * Starts the persistent shell session if not already running.
   * Must be called once (e.g. from Application or first screen visit).
   */
  fun startSession() = sessionLock.withLock {
    if (isSessionAlive) return
    Log.d(TAG, "Checkpoint 1: Preparing persistent shell session (HOME=${sandboxRoot.absolutePath})")

    try {
      // Resolve internal sysroot paths via EnvironmentInstaller.
      val prefix = EnvironmentInstaller.prefixDir(context)
      val binDir = EnvironmentInstaller.binDir(context)
      val libDir = EnvironmentInstaller.libDir(context)
      val shell = EnvironmentInstaller.shellPath(context)

      // Build PATH: include internal bin dir only if the bootstrap is installed.
      Log.d(TAG, "Checkpoint 2: Building environment (PREFIX=${prefix.absolutePath})")
      val basePath = "/system/bin:/system/xbin:" + (System.getenv("PATH") ?: "")
      val effectivePath = if (binDir.isDirectory) "${binDir.absolutePath}:$basePath" else basePath

      val env = mutableMapOf(
        "HOME" to sandboxRoot.absolutePath,
        "TERM" to "dumb",
        "PATH" to effectivePath,
      )
      // Inject sysroot environment variables when the bootstrap prefix exists.
      // This gives Shell_Execute access to bash, python, node, pkg, git, and native libs.
      if (prefix.isDirectory) {
        env["PREFIX"] = prefix.absolutePath
        env["LD_LIBRARY_PATH"] = libDir.absolutePath
      }

      Log.d(TAG, "Checkpoint 3: Starting ProcessBuilder (shell=$shell)")
      val pb = ProcessBuilder(shell)
        .directory(sandboxRoot)
        .redirectErrorStream(false)

      pb.environment().putAll(env)

      Log.d(TAG, "Checkpoint 4: Spawning shell process")
      val proc = pb.start()
      process = proc
      stdinWriter = OutputStreamWriter(proc.outputStream, Charsets.UTF_8)
      isSessionAlive = true

      Log.d(TAG, "Checkpoint 5: Opening stream readers")
      appendSystemLine("[MSTR_CTRL] Session started — HOME=${sandboxRoot.absolutePath}")

      // Background reader for stdout.
      scope.launch {
        try {
          BufferedReader(InputStreamReader(proc.inputStream, Charsets.UTF_8)).use { reader ->
            var line = reader.readLine()
            while (line != null) {
              appendLine(TerminalLine(line, LineSource.STDOUT))
              line = reader.readLine()
            }
          }
        } catch (_: Exception) { /* stream closed */ }
        isSessionAlive = false
        appendSystemLine("[MSTR_CTRL] Session ended.")
      }

      // Background reader for stderr.
      scope.launch {
        try {
          BufferedReader(InputStreamReader(proc.errorStream, Charsets.UTF_8)).use { reader ->
            var line = reader.readLine()
            while (line != null) {
              appendLine(TerminalLine(line, LineSource.STDERR))
              line = reader.readLine()
            }
          }
        } catch (_: Exception) { /* stream closed */ }
      }

      Log.d(TAG, "Checkpoint 6: Session fully started — running pre-flight check")
      // Pre-flight firmware bootstrap.
      runPreFlightCheck()
    } catch (e: SecurityException) {
      Log.e(TAG, "startSession: blocked by Android security (W^X / SELinux)", e)
      appendSystemLine("[MSTR_CTRL] ERROR: Terminal execution blocked by Android OS Security (W^X). Shell unavailable.")
      isSessionAlive = false
    } catch (e: UnsatisfiedLinkError) {
      Log.e(TAG, "startSession: native library load failed", e)
      appendSystemLine("[MSTR_CTRL] ERROR: Terminal native library missing. Shell unavailable.")
      isSessionAlive = false
    } catch (e: Throwable) {
      Log.e(TAG, "startSession: unexpected fatal error (${e.javaClass.simpleName})", e)
      appendSystemLine("[MSTR_CTRL] ERROR: Terminal init failed — ${e.message}")
      isSessionAlive = false
    }
  }

  /**
   * Sends a command into the persistent shell session.
   * The command text and its output are recorded in [outputLines].
   *
   * @param command  Shell command string.
   * @param visible  When `true`, the command and its output are appended to
   *                 [outputLines] so the user can watch in real time on
   *                 MstrCtrlScreen. When `false`, output is still captured
   *                 but not appended to the visible buffer (for silent
   *                 Shell_Execute calls).
   * @return The combined stdout + stderr text produced by the command,
   *         or `"TIMEOUT ERROR"` if it exceeded [CMD_TIMEOUT_SECONDS].
   */
  fun sendCommand(command: String, visible: Boolean = true): String {
    if (!isSessionAlive) {
      startSession()
    }

    if (visible) {
      appendLine(TerminalLine("$ $command", LineSource.STDIN))
    }

    // Use the existing executeCommand for isolated, timeout-safe execution.
    // This avoids the complexity of demuxing the persistent session's output
    // per-command while still honouring the 10-second timeout contract.
    val output = executeCommandInSandbox(command)

    if (visible) {
      output.lines().forEach { line ->
        appendLine(TerminalLine(line, LineSource.STDOUT))
      }
    }

    return output
  }

  /**
   * Executes [command] via a one-shot process using the internal shell.
   * Captures stdout + stderr with a strict [CMD_TIMEOUT_SECONDS] timeout.
   */
  private fun executeCommandInSandbox(command: String): String {
    return try {
      Log.d(TAG, "executeCommandInSandbox: $command")

      val shell = EnvironmentInstaller.shellPath(context)
      val pb = ProcessBuilder(shell, "-c", command)
        .directory(sandboxRoot)
        .redirectErrorStream(false)

      pb.environment()["HOME"] = sandboxRoot.absolutePath
      // Inject internal sysroot environment so bash/python/node/pkg are visible.
      val binDir = EnvironmentInstaller.binDir(context)
      val prefix = EnvironmentInstaller.prefixDir(context)
      val libDir = EnvironmentInstaller.libDir(context)
      if (binDir.isDirectory) {
        val basePath = pb.environment()["PATH"] ?: "/system/bin:/system/xbin"
        pb.environment()["PATH"] = "${binDir.absolutePath}:$basePath"
        pb.environment()["PREFIX"] = prefix.absolutePath
        pb.environment()["LD_LIBRARY_PATH"] = libDir.absolutePath
      }

      val proc = pb.start()

      val stdoutRef = AtomicReference("")
      val stderrRef = AtomicReference("")

      val stdoutThread = Thread {
        stdoutRef.set(proc.inputStream.bufferedReader(Charsets.UTF_8).readText())
      }.also { it.start() }

      val stderrThread = Thread {
        stderrRef.set(proc.errorStream.bufferedReader(Charsets.UTF_8).readText())
      }.also { it.start() }

      val finished = proc.waitFor(CMD_TIMEOUT_SECONDS, TimeUnit.SECONDS)

      if (!finished) {
        proc.destroyForcibly()
        stdoutThread.interrupt()
        stderrThread.interrupt()
        Log.w(TAG, "executeCommandInSandbox: timed out after ${CMD_TIMEOUT_SECONDS}s")
        return "TIMEOUT ERROR"
      }

      // Bounded join to prevent indefinite hangs if a reader thread is stuck.
      stdoutThread.join(READER_THREAD_JOIN_TIMEOUT_MS)
      stderrThread.join(READER_THREAD_JOIN_TIMEOUT_MS)
      if (stdoutThread.isAlive) Log.w(TAG, "executeCommandInSandbox: stdout reader thread still alive after join timeout")
      if (stderrThread.isAlive) Log.w(TAG, "executeCommandInSandbox: stderr reader thread still alive after join timeout")

      val stdout = stdoutRef.get()
      val stderr = stderrRef.get()
      val combined = buildString {
        if (stdout.isNotEmpty()) append(stdout)
        if (stderr.isNotEmpty()) {
          if (isNotEmpty()) append("\n")
          append(stderr)
        }
      }

      Log.d(TAG, "executeCommandInSandbox: exit=${proc.exitValue()}, ${combined.length} chars")
      combined.ifEmpty { "(no output)" }
    } catch (e: SecurityException) {
      Log.e(TAG, "executeCommandInSandbox: W^X / SELinux block", e)
      "ERROR: [System: Terminal execution blocked by Android OS Security (W^X). Fallback required.]"
    } catch (e: UnsatisfiedLinkError) {
      Log.e(TAG, "executeCommandInSandbox: native lib missing", e)
      "ERROR: [System: Terminal native library unavailable. Shell execution disabled.]"
    } catch (e: Exception) {
      Log.e(TAG, "executeCommandInSandbox: exception", e)
      "ERROR: ${e.message}"
    }
  }

  /**
   * Executes [command] and returns a [Pair] of (exitCode, combinedOutput).
   * Used by the Auto-Validator to inspect the exit code separately.
   */
  fun executeCommandWithExitCode(command: String): Pair<Int, String> {
    return try {
      val shell = EnvironmentInstaller.shellPath(context)
      val pb = ProcessBuilder(shell, "-c", command)
        .directory(sandboxRoot)
        .redirectErrorStream(false)

      pb.environment()["HOME"] = sandboxRoot.absolutePath
      // Inject internal sysroot environment so bash/python/node/pkg are visible.
      val binDir = EnvironmentInstaller.binDir(context)
      val prefix = EnvironmentInstaller.prefixDir(context)
      val libDir = EnvironmentInstaller.libDir(context)
      if (binDir.isDirectory) {
        val basePath = pb.environment()["PATH"] ?: "/system/bin:/system/xbin"
        pb.environment()["PATH"] = "${binDir.absolutePath}:$basePath"
        pb.environment()["PREFIX"] = prefix.absolutePath
        pb.environment()["LD_LIBRARY_PATH"] = libDir.absolutePath
      }

      val proc = pb.start()

      val stdoutRef = AtomicReference("")
      val stderrRef = AtomicReference("")

      val stdoutThread = Thread {
        stdoutRef.set(proc.inputStream.bufferedReader(Charsets.UTF_8).readText())
      }.also { it.start() }

      val stderrThread = Thread {
        stderrRef.set(proc.errorStream.bufferedReader(Charsets.UTF_8).readText())
      }.also { it.start() }

      val finished = proc.waitFor(CMD_TIMEOUT_SECONDS, TimeUnit.SECONDS)

      if (!finished) {
        proc.destroyForcibly()
        stdoutThread.interrupt()
        stderrThread.interrupt()
        return Pair(-1, "TIMEOUT ERROR")
      }

      // Bounded join to prevent indefinite hangs if a reader thread is stuck.
      stdoutThread.join(READER_THREAD_JOIN_TIMEOUT_MS)
      stderrThread.join(READER_THREAD_JOIN_TIMEOUT_MS)
      if (stdoutThread.isAlive) Log.w(TAG, "executeCommandWithExitCode: stdout reader thread still alive after join timeout")
      if (stderrThread.isAlive) Log.w(TAG, "executeCommandWithExitCode: stderr reader thread still alive after join timeout")

      val stdout = stdoutRef.get()
      val stderr = stderrRef.get()
      val combined = buildString {
        if (stdout.isNotEmpty()) append(stdout)
        if (stderr.isNotEmpty()) {
          if (isNotEmpty()) append("\n")
          append(stderr)
        }
      }

      Pair(proc.exitValue(), combined)
    } catch (e: SecurityException) {
      Log.e(TAG, "executeCommandWithExitCode: W^X / SELinux block", e)
      Pair(-1, "ERROR: [System: Terminal execution blocked by Android OS Security (W^X). Fallback required.]")
    } catch (e: UnsatisfiedLinkError) {
      Log.e(TAG, "executeCommandWithExitCode: native lib missing", e)
      Pair(-1, "ERROR: [System: Terminal native library unavailable. Shell execution disabled.]")
    } catch (e: Exception) {
      Pair(-1, "ERROR: ${e.message}")
    }
  }

  /** Destroys the persistent session. */
  fun destroySession() = sessionLock.withLock {
    try { stdinWriter?.close() } catch (_: Exception) {}
    process?.destroyForcibly()
    process = null
    stdinWriter = null
    isSessionAlive = false
    appendSystemLine("[MSTR_CTRL] Session destroyed.")
  }

  /** Clears the visible output buffer. */
  fun clearOutput() {
    _outputLines.value = emptyList()
  }

  // ── Pre-flight firmware bootstrap ─────────────────────────────

  /**
   * Pre-flight bootstrap triggered on first app launch.
   *
   * Delegates to [EnvironmentInstaller.ensureInstalled] to download and
   * extract the Termux bootstrap sysroot if not already present. Once the
   * sysroot is ready, optionally installs development packages (git, python,
   * nodejs) via the internal `pkg` binary.
   *
   * Sets [preFlightReady] to `true` once the gate is clear so the AI can
   * initialize.
   */
  fun runPreFlightCheck() {
    if (firstBootHandled) {
      _preFlightReady.value = true
      return
    }

    val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    val alreadyDone = prefs.getBoolean(KEY_FIRST_BOOT_DONE, false)

    if (alreadyDone) {
      firstBootHandled = true
      _preFlightReady.value = true
      return
    }

    appendSystemLine("[FIRMWARE] First boot detected — bootstrapping Linux environment…")
    scope.launch {
      // Stage 1: Install the Termux bootstrap sysroot.
      EnvironmentInstaller.ensureInstalled(context)

      val installerState = EnvironmentInstaller.state.value
      if (installerState is EnvironmentInstaller.State.Ready) {
        appendSystemLine("[FIRMWARE] Bootstrap sysroot installed — ${EnvironmentInstaller.prefixDir(context).absolutePath}")

        // Stage 2: Use the newly installed pkg to arm the environment.
        val pkgBinary = File(EnvironmentInstaller.binDir(context), "pkg")
        val hasPkg = pkgBinary.exists() && pkgBinary.canExecute()

        if (hasPkg) {
          appendSystemLine("[FIRMWARE] pkg detected — installing development packages…")
          val cmd = "pkg update -y && pkg upgrade -y && pkg install git python nodejs -y 2>&1"
          val (exitCode, result) = executeCommandWithExitCode(cmd)
          result.lines().forEach { line ->
            appendLine(TerminalLine(line, LineSource.STDOUT))
          }

          if (exitCode == 0) {
            appendSystemLine("[FIRMWARE] Package install PASSED (exit 0).")
          } else {
            appendSystemLine("[FIRMWARE] Package install finished with exit code $exitCode — continuing.")
          }
        } else {
          appendSystemLine("[FIRMWARE] pkg not available in bootstrap — base sysroot only.")
        }
      } else if (installerState is EnvironmentInstaller.State.Failed) {
        appendSystemLine("[FIRMWARE] Bootstrap FAILED: ${installerState.message}")
        appendSystemLine("[FIRMWARE] Running in standard Android mode — limited shell only.")
      }

      appendSystemLine("[FIRMWARE] Pre-flight check complete.")
      prefs.edit().putBoolean(KEY_FIRST_BOOT_DONE, true).apply()

      firstBootHandled = true
      _preFlightReady.value = true
    }
  }

  // ── Internal helpers ─────────────────────────────────────────

  private fun appendLine(line: TerminalLine) {
    val current = _outputLines.value
    _outputLines.value = if (current.size >= MAX_OUTPUT_LINES) {
      current.drop(current.size - MAX_OUTPUT_LINES + 1) + line
    } else {
      current + line
    }
  }

  private fun appendSystemLine(text: String) {
    appendLine(TerminalLine(text, LineSource.SYSTEM))
  }
}
