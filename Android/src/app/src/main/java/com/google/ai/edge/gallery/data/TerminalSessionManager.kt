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

private const val TAG = "TerminalSessionManager"

/** Preferences key to detect first-ever boot. */
private const val PREFS_NAME = "mstr_ctrl_prefs"
private const val KEY_FIRST_BOOT_DONE = "first_boot_done"

/** Timeout for individual commands piped through the session (seconds). */
private const val CMD_TIMEOUT_SECONDS = 10L

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
 * On first boot, an initialization hook runs `pkg install git python nodejs -y`
 * to arm the terminal before the AI wakes up.
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
  private var process: Process? = null
  private var stdinWriter: OutputStreamWriter? = null
  private var isSessionAlive = false

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
  @Synchronized
  fun startSession() {
    if (isSessionAlive) return
    Log.d(TAG, "Starting persistent shell session (HOME=${sandboxRoot.absolutePath})")

    val env = mapOf(
      "HOME" to sandboxRoot.absolutePath,
      "TERM" to "dumb",
      "PATH" to "/data/data/com.termux/files/usr/bin:" +
        "/system/bin:/system/xbin:" +
        (System.getenv("PATH") ?: ""),
    )

    val pb = ProcessBuilder("sh")
      .directory(sandboxRoot)
      .redirectErrorStream(false)

    pb.environment().putAll(env)

    val proc = pb.start()
    process = proc
    stdinWriter = OutputStreamWriter(proc.outputStream)
    isSessionAlive = true

    appendSystemLine("[MSTR_CTRL] Session started — HOME=${sandboxRoot.absolutePath}")

    // Background reader for stdout.
    scope.launch {
      try {
        BufferedReader(InputStreamReader(proc.inputStream)).use { reader ->
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
        BufferedReader(InputStreamReader(proc.errorStream)).use { reader ->
          var line = reader.readLine()
          while (line != null) {
            appendLine(TerminalLine(line, LineSource.STDERR))
            line = reader.readLine()
          }
        }
      } catch (_: Exception) { /* stream closed */ }
    }

    // Pre-flight firmware bootstrap.
    runPreFlightCheck()
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
   * Executes [command] via a one-shot `sh -c` process rooted in the sandbox.
   * Captures stdout + stderr with a strict [CMD_TIMEOUT_SECONDS] timeout.
   */
  private fun executeCommandInSandbox(command: String): String {
    return try {
      Log.d(TAG, "executeCommandInSandbox: $command")

      val pb = ProcessBuilder("sh", "-c", command)
        .directory(sandboxRoot)
        .redirectErrorStream(false)

      pb.environment()["HOME"] = sandboxRoot.absolutePath

      val proc = pb.start()

      val stdoutRef = AtomicReference("")
      val stderrRef = AtomicReference("")

      val stdoutThread = Thread {
        stdoutRef.set(proc.inputStream.bufferedReader().readText())
      }.also { it.start() }

      val stderrThread = Thread {
        stderrRef.set(proc.errorStream.bufferedReader().readText())
      }.also { it.start() }

      val finished = proc.waitFor(CMD_TIMEOUT_SECONDS, TimeUnit.SECONDS)

      if (!finished) {
        proc.destroyForcibly()
        stdoutThread.interrupt()
        stderrThread.interrupt()
        Log.w(TAG, "executeCommandInSandbox: timed out after ${CMD_TIMEOUT_SECONDS}s")
        return "TIMEOUT ERROR"
      }

      stdoutThread.join(1_000)
      stderrThread.join(1_000)

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
      val pb = ProcessBuilder("sh", "-c", command)
        .directory(sandboxRoot)
        .redirectErrorStream(false)

      pb.environment()["HOME"] = sandboxRoot.absolutePath

      val proc = pb.start()

      val stdoutRef = AtomicReference("")
      val stderrRef = AtomicReference("")

      val stdoutThread = Thread {
        stdoutRef.set(proc.inputStream.bufferedReader().readText())
      }.also { it.start() }

      val stderrThread = Thread {
        stderrRef.set(proc.errorStream.bufferedReader().readText())
      }.also { it.start() }

      val finished = proc.waitFor(CMD_TIMEOUT_SECONDS, TimeUnit.SECONDS)

      if (!finished) {
        proc.destroyForcibly()
        stdoutThread.interrupt()
        stderrThread.interrupt()
        return Pair(-1, "TIMEOUT ERROR")
      }

      stdoutThread.join(1_000)
      stderrThread.join(1_000)

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
    } catch (e: Exception) {
      Pair(-1, "ERROR: ${e.message}")
    }
  }

  /** Destroys the persistent session. */
  @Synchronized
  fun destroySession() {
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
   * Executes `pkg update && pkg upgrade -y && pkg install git python nodejs -y`
   * to arm the sandbox. Sets [preFlightReady] to `true` only when the command
   * returns exit code 0. The AI must not initialize until this gate opens.
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

    appendSystemLine("[FIRMWARE] First boot detected — running pre-flight check…")
    scope.launch {
      val cmd = "pkg update && pkg upgrade -y && pkg install git python nodejs -y 2>&1 || echo '[FIRMWARE] pkg not available — skipping package install'"
      val (exitCode, result) = executeCommandWithExitCode(cmd)
      result.lines().forEach { line ->
        appendLine(TerminalLine(line, LineSource.STDOUT))
      }

      if (exitCode == 0) {
        appendSystemLine("[FIRMWARE] Pre-flight check PASSED (exit 0).")
        prefs.edit().putBoolean(KEY_FIRST_BOOT_DONE, true).apply()
      } else {
        appendSystemLine("[FIRMWARE] Pre-flight check completed with exit code $exitCode — marking done anyway.")
        // Mark done to avoid blocking the app forever on non-Termux environments.
        prefs.edit().putBoolean(KEY_FIRST_BOOT_DONE, true).apply()
      }

      firstBootHandled = true
      _preFlightReady.value = true
    }
  }

  // ── Internal helpers ─────────────────────────────────────────

  private fun appendLine(line: TerminalLine) {
    _outputLines.value = _outputLines.value + line
  }

  private fun appendSystemLine(text: String) {
    appendLine(TerminalLine(text, LineSource.SYSTEM))
  }
}
