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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
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

/** Timeout in seconds before a shell process is forcibly killed. */
private const val CMD_TIMEOUT_SECONDS = 60L

/** Maximum time (ms) to wait for reader threads to finish after the process exits. */
private const val READER_THREAD_JOIN_TIMEOUT_MS = 5_000L

/** Maximum number of lines retained in the visible output buffer to limit GC pressure. */
private const val MAX_OUTPUT_LINES = 2000

/** Polling interval (ms) when waiting for command output from the PTY. */
private const val PTY_POLL_INTERVAL_MS = 50L

/** Maximum ms of silence before we consider a PTY command complete. */
private const val PTY_SILENCE_THRESHOLD_MS = 300L

/**
 * Extended timeout (seconds) used for package-manager operations that require
 * network I/O: `pkg update`, `pkg upgrade`, `pkg install`, `pip install`, etc.
 * Network latency on mobile can push these well past the normal 60-second cap.
 */
private const val PKG_INSTALL_TIMEOUT_SECONDS = 300L

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

  // ── PTY bridge (preferred execution path) ─────────────────────
  /**
   * The [TermuxSessionBridge] provides a PTY-backed shell session.
   * When available, [sendCommand] pipes directly into the PTY and
   * captures output by waiting for silence or the prompt delimiter.
   */
  val ptyBridge: TermuxSessionBridge by lazy { TermuxSessionBridge(context) }

  /** True if the PTY bridge session is alive and usable. */
  val isPtyAlive: Boolean get() = ptyBridge.isAlive

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

  // ── Terminal online state ────────────────────────────────────
  private val _terminalOnline = MutableStateFlow(false)

  /**
   * Emits `true` once the agentic verification loop confirms that `pkg`
   * is working (`pkg update -y` returns a `$` prompt delimiter without
   * error). The UI binds to this to display "TERMINAL: ONLINE".
   */
  val terminalOnline: StateFlow<Boolean> = _terminalOnline.asStateFlow()

  // ── Public API ───────────────────────────────────────────────

  /**
   * Starts the persistent shell session if not already running.
   * Must be called once (e.g. from Application or first screen visit).
   */
  fun startSession() = sessionLock.withLock {
    if (isSessionAlive) return@withLock
    Log.d(TAG, "Checkpoint 1: Preparing persistent shell session (HOME=${sandboxRoot.absolutePath})")

    try {
      // Resolve internal sysroot paths via EnvironmentInstaller.
      val prefix = EnvironmentInstaller.prefixDir(context)
      val binDir = EnvironmentInstaller.binDir(context)
      val libDir = EnvironmentInstaller.libDir(context)
      val shell = EnvironmentInstaller.shellPath(context)

      // Build PATH: include internal bin and applets dirs only if the bootstrap is installed.
      // $PREFIX/bin/applets contains Termux busybox applets required by pkg and other scripts.
      Log.d(TAG, "Checkpoint 2: Building environment (PREFIX=${prefix.absolutePath})")
      val appletsDir = File(binDir, "applets")
      val effectivePath = buildString {
        if (binDir.isDirectory) {
          append(binDir.absolutePath)
          if (appletsDir.isDirectory) append(":${appletsDir.absolutePath}")
          append(":")
        }
        append("/system/bin:/system/xbin")
      }

      val env = mutableMapOf(
        "HOME" to EnvironmentInstaller.homeDir(context).also { it.mkdirs() }.absolutePath,
        "TERM" to "xterm-256color",
        "PATH" to effectivePath,
        "TMPDIR" to EnvironmentInstaller.tmpDir(context).also { it.mkdirs() }.absolutePath,
        "LANG" to "en_US.UTF-8",
        "SHELL" to EnvironmentInstaller.shellPath(context),
      )
      // Inject sysroot environment variables when the bootstrap prefix exists.
      // This gives Shell_Execute access to bash, python, node, pkg, git, and native libs.
      if (prefix.isDirectory) {
        env["PREFIX"] = prefix.absolutePath
        env["LD_LIBRARY_PATH"] = libDir.absolutePath
        // TERMUX_PREFIX is read by Termux's runtime-patched apt/dpkg to locate
        // their config directories at runtime instead of the compiled-in Termux
        // default path.  Required for `pkg install` / `apt-get` to work.
        env["TERMUX_PREFIX"] = prefix.absolutePath
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

      // Defer PTY bridge creation until the bootstrap reaches a terminal state
      // (Ready or Failed).  Creating the session immediately after startSession()
      // would race the bootstrap download and cause the PTY to launch with
      // /system/bin/sh (no pkg, no apt) instead of $PREFIX/bin/bash.
      Log.d(TAG, "Checkpoint 7: Deferring PTY bridge start until bootstrap is settled")
      scope.launch {
        // Wait for the installer coroutine (launched by runPreFlightCheck) to finish.
        EnvironmentInstaller.state.first {
          it is EnvironmentInstaller.State.Ready || it is EnvironmentInstaller.State.Failed
        }
        // Use sessionLock to prevent concurrent session creation with sendCommand().
        sessionLock.withLock {
          if (!ptyBridge.isAlive) {
            ptyBridge.createSession(sandboxRoot)
            if (ptyBridge.sessionInitFailed) {
              Log.w(TAG, "PTY bridge init failed: ${ptyBridge.sessionInitError} — falling back to ProcessBuilder for sendCommand")
              appendSystemLine("[MSTR_CTRL] PTY bridge unavailable — using process-based fallback.")
            } else {
              appendSystemLine("[MSTR_CTRL] PTY bridge active — xterm-256color")
            }
          }
        }
      }
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
   * Sends a command into the terminal session.
   *
   * **Execution strategy (per the CLU/BOX Terminal Architecture):**
   * 1. If the [ptyBridge] is alive, pipe the command directly into the PTY's
   *    outputStream followed by `\n`. Wait for the PTY inputStream to go silent
   *    (no new data for [PTY_SILENCE_THRESHOLD_MS]) or hit a prompt delimiter
   *    (`$ `) before capturing the buffer and returning the output.
   * 2. If the PTY is unavailable (e.g. W^X block), fall back to
   *    [executeCommandInSandbox] which uses a one-shot process with strict timeout.
   *
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

    // If the bootstrap installer is actively running, block until it reaches
    // a terminal state before dispatching the command.  This prevents commands
    // from landing in /system/bin/sh while the Termux sysroot is downloading.
    val installerState = EnvironmentInstaller.state.value
    if (installerState is EnvironmentInstaller.State.Downloading ||
        installerState is EnvironmentInstaller.State.Extracting ||
        installerState is EnvironmentInstaller.State.FixingPermissions) {
      runBlocking(Dispatchers.IO) {
        EnvironmentInstaller.state.first {
          it is EnvironmentInstaller.State.Ready || it is EnvironmentInstaller.State.Failed
        }
      }
      // Bootstrap just completed — if the deferred PTY coroutine hasn't run yet,
      // start the session with the correct bash shell immediately.
      // Double-checked locking with sessionLock prevents concurrent creation
      // with the deferred coroutine in startSession().
      if (!ptyBridge.isAlive && EnvironmentInstaller.state.value is EnvironmentInstaller.State.Ready) {
        sessionLock.withLock {
          if (!ptyBridge.isAlive) {
            ptyBridge.createSession(sandboxRoot)
          }
        }
      }
    }

    if (visible) {
      appendLine(TerminalLine("$ $command", LineSource.STDIN))
    }

    // Prefer the PTY bridge (true terminal piping) when available.
    val output = if (ptyBridge.isAlive) {
      sendCommandViaPty(command)
    } else {
      // Fallback: isolated, timeout-safe one-shot execution.
      executeCommandInSandbox(command)
    }

    if (visible) {
      output.lines().forEach { line ->
        appendLine(TerminalLine(line, LineSource.STDOUT))
      }
    }

    return output
  }

  /**
   * Pipes [command] into the PTY session's stdin and waits for the output
   * to settle (silence detection) or hit the prompt delimiter.
   *
   * The PTY bridge's [TerminalSession] emulator transcript is read via
   * [TerminalBuffer.getTranscriptText] — we snapshot the transcript text
   * before writing, poll until it stops changing (silence), then diff the
   * before/after strings to extract the command's output.
   *
   * This implements the `[ACTION: bash]` → wait-for-silence → `[OBSERVE]`
   * state machine loop required by the CLU/BOX terminal architecture.
   */
  private fun sendCommandViaPty(command: String): String {
    val session = ptyBridge.terminalSession ?: return executeCommandInSandbox(command)
    val emulator = session.emulator ?: return executeCommandInSandbox(command)

    // Snapshot transcript text before injecting the command.
    val preText = emulator.screen.getTranscriptText()

    // Pipe the command directly into the PTY stdin followed by \n.
    ptyBridge.sendCommand(command)

    // Wait for output to settle: poll the transcript text for changes.
    val deadlineMs = System.currentTimeMillis() + (CMD_TIMEOUT_SECONDS * 1000)
    var lastChangeMs = System.currentTimeMillis()
    var lastText = preText

    while (System.currentTimeMillis() < deadlineMs) {
      Thread.sleep(PTY_POLL_INTERVAL_MS)

      val currentText = emulator.screen.getTranscriptText()
      if (currentText != lastText) {
        lastText = currentText
        lastChangeMs = System.currentTimeMillis()
        continue
      }

      // Check if silence threshold exceeded — output has settled.
      if (System.currentTimeMillis() - lastChangeMs >= PTY_SILENCE_THRESHOLD_MS) {
        break
      }
    }

    // Extract new content added since the command was sent.
    val postText = emulator.screen.getTranscriptText()
    val trimmedPre = preText.trimEnd()
    val newContent = if (postText.length > trimmedPre.length &&
        postText.startsWith(trimmedPre)) {
      postText.substring(trimmedPre.length).trimStart('\n')
    } else {
      // Fallback: return everything that's visible (transcript may have wrapped).
      postText
    }

    // Strip trailing prompt line.
    val lines = newContent.lines().toMutableList()
    if (lines.isNotEmpty()) {
      val last = lines.last().trimEnd()
      if (last.endsWith("$") || last.contains("CLU/BOX $")) {
        lines.removeAt(lines.lastIndex)
      }
    }

    val result = lines.joinToString("\n").trimEnd()
    return if (result.isBlank()) "(no output)" else result
  }

  /**
   * Returns the text content of the last visible line in the emulator
   * by reading the full transcript and taking the final non-empty line.
   */
  private fun getLastVisibleLine(emulator: com.termux.terminal.TerminalEmulator): String {
    return try {
      val text = emulator.screen.getTranscriptText()
      val trimmed = text.trimEnd()
      trimmed.substringAfterLast('\n').ifEmpty { trimmed }
    } catch (_: Exception) { "" }
  }

  /**
   * Executes [command] via a one-shot process using the internal shell.
   * Captures stdout + stderr with a strict [CMD_TIMEOUT_SECONDS] timeout.
   */
  private fun executeCommandInSandbox(command: String): String {
    return try {
      Log.d(TAG, "executeCommandInSandbox: $command")

      val shellPath = EnvironmentInstaller.shellPath(context)
      val pb = ProcessBuilder(shellPath, "-c", command)
        .directory(sandboxRoot)
        .redirectErrorStream(false)

      pb.environment()["HOME"] = EnvironmentInstaller.homeDir(context).also { it.mkdirs() }.absolutePath
      pb.environment()["TMPDIR"] = EnvironmentInstaller.tmpDir(context).also { it.mkdirs() }.absolutePath
      pb.environment()["LANG"] = "en_US.UTF-8"
      pb.environment()["SHELL"] = EnvironmentInstaller.shellPath(context)
      // Inject internal sysroot environment so bash/python/node/pkg are visible.
      val binDir = EnvironmentInstaller.binDir(context)
      val prefix = EnvironmentInstaller.prefixDir(context)
      val libDir = EnvironmentInstaller.libDir(context)
      if (binDir.isDirectory) {
        val appletsDir = File(binDir, "applets")
        val basePath = pb.environment()["PATH"] ?: "/system/bin:/system/xbin"
        val fullPath = buildString {
          append(binDir.absolutePath)
          if (appletsDir.isDirectory) append(":${appletsDir.absolutePath}")
          append(":$basePath")
        }
        pb.environment()["PATH"] = fullPath
        pb.environment()["PREFIX"] = prefix.absolutePath
        pb.environment()["LD_LIBRARY_PATH"] = libDir.absolutePath
        // Required for Termux-patched apt/dpkg to find config at our prefix.
        pb.environment()["TERMUX_PREFIX"] = prefix.absolutePath
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
  fun executeCommandWithExitCode(command: String, timeoutSeconds: Long = CMD_TIMEOUT_SECONDS): Pair<Int, String> {
    return try {
      val shellPath = EnvironmentInstaller.shellPath(context)
      val pb = ProcessBuilder(shellPath, "-c", command)
        .directory(sandboxRoot)
        .redirectErrorStream(false)

      pb.environment()["HOME"] = EnvironmentInstaller.homeDir(context).also { it.mkdirs() }.absolutePath
      pb.environment()["TMPDIR"] = EnvironmentInstaller.tmpDir(context).also { it.mkdirs() }.absolutePath
      pb.environment()["LANG"] = "en_US.UTF-8"
      pb.environment()["SHELL"] = EnvironmentInstaller.shellPath(context)
      // Inject internal sysroot environment so bash/python/node/pkg are visible.
      val binDir = EnvironmentInstaller.binDir(context)
      val prefix = EnvironmentInstaller.prefixDir(context)
      val libDir = EnvironmentInstaller.libDir(context)
      if (binDir.isDirectory) {
        val appletsDir = File(binDir, "applets")
        val basePath = pb.environment()["PATH"] ?: "/system/bin:/system/xbin"
        val fullPath = buildString {
          append(binDir.absolutePath)
          if (appletsDir.isDirectory) append(":${appletsDir.absolutePath}")
          append(":$basePath")
        }
        pb.environment()["PATH"] = fullPath
        pb.environment()["PREFIX"] = prefix.absolutePath
        pb.environment()["LD_LIBRARY_PATH"] = libDir.absolutePath
        // Required for Termux-patched apt/dpkg to find config at our prefix.
        pb.environment()["TERMUX_PREFIX"] = prefix.absolutePath
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

      val finished = proc.waitFor(timeoutSeconds, TimeUnit.SECONDS)

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

  /** Destroys the persistent session and the PTY bridge. */
  fun destroySession() = sessionLock.withLock {
    try { stdinWriter?.close() } catch (_: Exception) {}
    process?.destroyForcibly()
    process = null
    stdinWriter = null
    isSessionAlive = false
    ptyBridge.destroySession()
    appendSystemLine("[MSTR_CTRL] Session destroyed.")
  }

  /** Clears the visible output buffer. */
  fun clearOutput() {
    _outputLines.value = emptyList()
  }

  // ── Pre-flight firmware bootstrap ─────────────────────────────

  /**
   * Validates that the essential binaries (`bash`, `git`) are executable.
   *
   * Runs `bash --version` and `git --version` internally. If either returns
   * exit code 127 (Not Found) or 13 (Permission Denied), the method
   * automatically re-runs the extraction logic and applies `chmod -R 700`
   * to the bin directory.
   *
   * @return `true` if the environment is healthy after the check (possibly
   *         after self-healing), `false` if repair also failed.
   */
  fun checkEnvironment(): Boolean {
    val binDir = EnvironmentInstaller.binDir(context)
    if (!binDir.isDirectory) {
      Log.w(TAG, "checkEnvironment: binDir does not exist — environment not installed")
      return false
    }

    // Only check bash: git is NOT in the base Termux bootstrap and must be
    // installed separately via pkg.  Checking git here causes a false
    // "Self-heal FAILED" and makes checkEnvironment() return false even
    // when the environment is perfectly functional.
    val checks = listOf("bash --version")
    var needsRepair = false

    for (cmd in checks) {
      val (exitCode, _) = executeCommandWithExitCode(cmd)
      if (exitCode == 127 || exitCode == 13 || exitCode == 126) {
        Log.w(TAG, "checkEnvironment: '$cmd' returned exit $exitCode — self-heal triggered")
        needsRepair = true
        break
      }
    }

    if (!needsRepair) {
      Log.i(TAG, "checkEnvironment: environment healthy")
      return true
    }

    // Self-healing: re-fix permissions on the entire bin directory.
    appendSystemLine("[FIRMWARE] Self-heal: fixing permissions on ${binDir.absolutePath}")
    val chmodResult = executeCommandWithExitCode("chmod -R 700 ${binDir.absolutePath}")
    Log.d(TAG, "checkEnvironment: chmod exit=${chmodResult.first}")

    // Also walk the directory with Java API as a fallback (chmod may not
    // be available if the sysroot is completely broken).
    binDir.walkTopDown().filter { it.isFile }.forEach { file ->
      file.setExecutable(true, false)
      file.setReadable(true, false)
    }

    // Re-validate after repair.
    for (cmd in checks) {
      val (exitCode, _) = executeCommandWithExitCode(cmd)
      if (exitCode == 127 || exitCode == 13 || exitCode == 126) {
        Log.e(TAG, "checkEnvironment: self-heal failed — '$cmd' still returns exit $exitCode")
        appendSystemLine("[FIRMWARE] Self-heal FAILED for '$cmd' (exit $exitCode). Environment degraded.")
        return false
      }
    }

    appendSystemLine("[FIRMWARE] Self-heal PASSED — environment restored.")
    return true
  }

  /**
   * Pre-flight bootstrap triggered on first app launch.
   *
   * Delegates to [EnvironmentInstaller.ensureInstalled] to download and
   * extract the Termux bootstrap sysroot if not already present. Once the
   * sysroot is ready, optionally installs development packages (git, python,
   * nodejs) via the internal `pkg` binary.
   *
   * After environment provisioning completes, runs [checkEnvironment] to
   * validate/self-heal, then executes agentic verification commands
   * (`pip install requests`, `git config`) to confirm "Dev-Ready" status.
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

    if (alreadyDone && EnvironmentInstaller.isInstalled(context)) {
      // Subsequent boot with a verified sysroot: run the self-heal check to
      // fix any permission regressions from OTA updates or storage clears.
      scope.launch {
        val healthy = checkEnvironment()
        if (healthy) _terminalOnline.value = true
        firstBootHandled = true
        _preFlightReady.value = true
      }
      return
    }

    // If we reach here it is either a genuine first boot, OR a subsequent
    // boot where the previous run wrote KEY_FIRST_BOOT_DONE=true but then
    // crashed before the bootstrap was fully extracted (e.g. zip corruption
    // from a concurrent download race).  In both cases we must run the full
    // install path again — ensureInstalled() will skip the download if the
    // sysroot is already healthy.
    if (alreadyDone) {
      appendSystemLine("[FIRMWARE] Sysroot missing on subsequent boot — re-bootstrapping…")
    } else {
      appendSystemLine("[FIRMWARE] First boot detected — bootstrapping Linux environment…")
    }
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
          appendSystemLine("[FIRMWARE] pkg detected — running update (best-effort)…")
          // Run pkg update as a best-effort network check; the exit code does
          // NOT gate terminalOnline — a network failure must not leave the
          // terminal permanently OFFLINE when the sysroot is functional.
          val pkgUpdateCmd = "pkg update -y 2>&1"
          val (updateExit, updateResult) = executeCommandWithExitCode(pkgUpdateCmd, PKG_INSTALL_TIMEOUT_SECONDS)
          updateResult.lines().forEach { line ->
            appendLine(TerminalLine(line, LineSource.STDOUT))
          }

          if (updateExit == 0) {
            appendSystemLine("[FIRMWARE] pkg update completed successfully.")
          } else {
            appendSystemLine("[FIRMWARE] pkg update finished with exit $updateExit (non-fatal — continuing).")
          }

          // Also install core development packages.
          appendSystemLine("[FIRMWARE] Installing development packages…")
          val installCmd = "pkg upgrade -y && pkg install git python nodejs -y 2>&1"
          val (installExit, installResult) = executeCommandWithExitCode(installCmd, PKG_INSTALL_TIMEOUT_SECONDS)
          installResult.lines().forEach { line ->
            appendLine(TerminalLine(line, LineSource.STDOUT))
          }
          if (installExit == 0) {
            appendSystemLine("[FIRMWARE] Package install PASSED (exit 0).")
          } else {
            appendSystemLine("[FIRMWARE] Package install finished with exit code $installExit — continuing.")
          }
        } else {
          appendSystemLine("[FIRMWARE] pkg not available in bootstrap — base sysroot only.")
        }

        // Stage 3: Validate environment with self-healing.
        appendSystemLine("[FIRMWARE] Running environment validation…")
        val envHealthy = checkEnvironment()

        // The terminal is ONLINE as soon as the sysroot is functional —
        // regardless of whether pkg update or package installs succeeded.
        if (envHealthy) {
          _terminalOnline.value = true
          appendSystemLine("[FIRMWARE] TERMINAL: ONLINE")
        }

        // Stage 4: Agentic verification — confirm Dev-Ready status.
        if (envHealthy) {
          appendSystemLine("[FIRMWARE] Agentic verification: installing pip packages…")
          val (pipExit, pipOut) = executeCommandWithExitCode("pip install requests 2>&1", PKG_INSTALL_TIMEOUT_SECONDS)
          if (pipExit == 0) {
            appendSystemLine("[FIRMWARE] pip install requests — OK")
          } else {
            appendSystemLine("[FIRMWARE] pip install requests — exit $pipExit (non-critical)")
          }

          appendSystemLine("[FIRMWARE] Agentic verification: configuring git…")
          val (gitExit, gitOut) = executeCommandWithExitCode(
            "git config --global init.defaultBranch main 2>&1"
          )
          if (gitExit == 0) {
            appendSystemLine("[FIRMWARE] git config — OK")
          } else {
            appendSystemLine("[FIRMWARE] git config — exit $gitExit (non-critical)")
          }

          appendSystemLine("[FIRMWARE] Environment is Dev-Ready.")
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
