/*
 * Copyright 2026 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
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

private const val PREFS_NAME = "mstr_ctrl_prefs"
private const val KEY_FIRST_BOOT_DONE = "first_boot_done"
private const val CMD_TIMEOUT_SECONDS = 60L
private const val READER_THREAD_JOIN_TIMEOUT_MS = 5_000L
private const val MAX_OUTPUT_LINES = 2000
private const val PTY_POLL_INTERVAL_MS = 50L
private const val PTY_SILENCE_THRESHOLD_MS = 300L
private const val PKG_INSTALL_TIMEOUT_SECONDS = 300L

data class TerminalLine(
  val text: String,
  val source: LineSource,
)

enum class LineSource { STDIN, STDOUT, STDERR, SYSTEM }

class TerminalSessionManager(private val context: Context) {

  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

  val sandboxRoot: File by lazy {
    File(context.filesDir, "clu_file_box").also { it.mkdirs() }
  }

  val ptyBridge: TermuxSessionBridge by lazy { TermuxSessionBridge(context) }
  val isPtyAlive: Boolean get() = ptyBridge.isAlive

  private val _outputLines = MutableStateFlow<List<TerminalLine>>(emptyList())
  val outputLines: StateFlow<List<TerminalLine>> = _outputLines.asStateFlow()

  private val sessionLock = ReentrantLock()
  private var process: Process? = null
  private var stdinWriter: OutputStreamWriter? = null
  @Volatile private var isSessionAlive = false
  private var firstBootHandled = false

  private val _preFlightReady = MutableStateFlow(false)
  val preFlightReady: StateFlow<Boolean> = _preFlightReady.asStateFlow()

  private val _terminalOnline = MutableStateFlow(false)
  val terminalOnline: StateFlow<Boolean> = _terminalOnline.asStateFlow()

  fun startSession() = sessionLock.withLock {
    if (isSessionAlive) return@withLock
    Log.d(TAG, "Checkpoint 1: Preparing persistent shell session (HOME=${sandboxRoot.absolutePath})")

    try {
      val prefix = EnvironmentInstaller.prefixDir(context)
      val shell = EnvironmentInstaller.shellPath(context)

      Log.d(TAG, "Checkpoint 2: Building environment (PREFIX=${prefix.absolutePath})")
      val matrixRoot = "${context.filesDir.absolutePath}/matrix"
      File("$matrixRoot/data/data/com.termux/files/usr").mkdirs()
      File(context.filesDir, "tmp").mkdirs()

      val env = mutableMapOf(
        "HOME" to sandboxRoot.absolutePath,
        "TERM" to "xterm-256color",
        "PATH" to "/data/data/com.termux/files/usr/bin:/system/bin",
        "TMPDIR" to EnvironmentInstaller.tmpDir(context).also { it.mkdirs() }.absolutePath,
        "LANG" to "en_US.UTF-8",
        "SHELL" to EnvironmentInstaller.shellPath(context),
      )
      
      if (prefix.isDirectory) {
        env["PREFIX"] = prefix.absolutePath
        env["TERMUX_PREFIX"] = prefix.absolutePath
      }
      
      env["PROOT_TMP_DIR"] = File(context.filesDir, "tmp").absolutePath
      env["PROOT_NO_SECCOMP"] = "1"
      env["PROOT_NO_SYSVIPC"] = "1"

      Log.d(TAG, "Checkpoint 3: Starting ProcessBuilder")
      val usingProot = EnvironmentInstaller.prootPath(context).let { it.exists() && it.canExecute() } &&
                       EnvironmentInstaller.bashPath(context).let { it.exists() && it.canExecute() }
      val isBash = usingProot || shell.endsWith("bash")
      val shellArgs = if (isBash) arrayOf("--login") else emptyArray()
      val shellCmd = EnvironmentInstaller.buildShellCommand(context, shellArgs)
      val pb = ProcessBuilder(shellCmd)
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

      scope.launch {
        try {
          BufferedReader(InputStreamReader(proc.inputStream, Charsets.UTF_8)).use { reader ->
            var line = reader.readLine()
            while (line != null) {
              appendLine(TerminalLine(line, LineSource.STDOUT))
              line = reader.readLine()
            }
          }
        } catch (_: Exception) { }
        isSessionAlive = false
        appendSystemLine("[MSTR_CTRL] Session ended.")
      }

      scope.launch {
        try {
          BufferedReader(InputStreamReader(proc.errorStream, Charsets.UTF_8)).use { reader ->
            var line = reader.readLine()
            while (line != null) {
              appendLine(TerminalLine(line, LineSource.STDERR))
              line = reader.readLine()
            }
          }
        } catch (_: Exception) { }
      }

      Log.d(TAG, "Checkpoint 6: Session fully started — running pre-flight check")
      runPreFlightCheck()

      Log.d(TAG, "Checkpoint 7: Deferring PTY bridge start until bootstrap is settled")
      scope.launch {
        EnvironmentInstaller.state.first {
          it is EnvironmentInstaller.State.Ready || it is EnvironmentInstaller.State.Failed
        }
        sessionLock.withLock {
          if (!ptyBridge.isAlive) {
            ptyBridge.createSession(sandboxRoot)
            if (ptyBridge.sessionInitFailed) {
              Log.w(TAG, "PTY bridge init failed: ${ptyBridge.sessionInitError}")
              appendSystemLine("[MSTR_CTRL] PTY bridge unavailable.")
            } else {
              appendSystemLine("[MSTR_CTRL] PTY bridge active — xterm-256color")
            }
          }
        }
      }
    } catch (e: Exception) {
      Log.e(TAG, "startSession failed", e)
      appendSystemLine("[MSTR_CTRL] ERROR: Terminal init failed — ${e.message}")
      isSessionAlive = false
    }
  }

  fun sendCommand(command: String, visible: Boolean = true): String {
    if (!isSessionAlive) {
      startSession()
    }

    val installerState = EnvironmentInstaller.state.value
    if (installerState is EnvironmentInstaller.State.Downloading ||
        installerState is EnvironmentInstaller.State.Extracting ||
        installerState is EnvironmentInstaller.State.FixingPermissions) {
      runBlocking(Dispatchers.IO) {
        EnvironmentInstaller.state.first {
          it is EnvironmentInstaller.State.Ready || it is EnvironmentInstaller.State.Failed
        }
      }
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

    val output = if (ptyBridge.isAlive) {
      sendCommandViaPty(command)
    } else {
      executeCommandInSandbox(command)
    }

    if (visible) {
      output.lines().forEach { line ->
        appendLine(TerminalLine(line, LineSource.STDOUT))
      }
    }

    return output
  }

  private fun sendCommandViaPty(command: String): String {
    val session = ptyBridge.terminalSession ?: return executeCommandInSandbox(command)
    val emulator = session.emulator ?: return executeCommandInSandbox(command)

    val preText = emulator.screen.getTranscriptText()
    ptyBridge.sendCommand(command)

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
      if (System.currentTimeMillis() - lastChangeMs >= PTY_SILENCE_THRESHOLD_MS) {
        break
      }
    }

    val postText = emulator.screen.getTranscriptText()
    val trimmedPre = preText.trimEnd()
    val newContent = if (postText.length > trimmedPre.length &&
        postText.startsWith(trimmedPre)) {
      postText.substring(trimmedPre.length).trimStart('\n')
    } else {
      postText
    }

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

  private fun executeCommandInSandbox(command: String): String {
    return try {
      val cmd = EnvironmentInstaller.buildShellCommand(context, arrayOf("-c", command))
      val pb = ProcessBuilder(cmd)
        .directory(sandboxRoot)
        .redirectErrorStream(false)

      pb.environment()["HOME"]           = sandboxRoot.absolutePath
      pb.environment()["TMPDIR"]         = EnvironmentInstaller.tmpDir(context).also { it.mkdirs() }.absolutePath
      pb.environment()["LANG"]           = "en_US.UTF-8"
      pb.environment()["SHELL"]          = EnvironmentInstaller.shellPath(context)
      pb.environment()["PATH"]           = "/data/data/com.termux/files/usr/bin:/system/bin"
      pb.environment()["TERM"]           = "xterm-256color"
      
      val prefix = EnvironmentInstaller.prefixDir(context)
      if (prefix.isDirectory) {
        pb.environment()["PREFIX"]         = prefix.absolutePath
        pb.environment()["TERMUX_PREFIX"]  = prefix.absolutePath
      }
      pb.environment()["PROOT_TMP_DIR"]    = File(context.filesDir, "tmp").absolutePath
      pb.environment()["PROOT_NO_SECCOMP"] = "1"
      pb.environment()["PROOT_NO_SYSVIPC"] = "1"

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
        try { proc.inputStream.close() } catch (e: Exception) {}
        try { proc.errorStream.close() } catch (e: Exception) {}
        stdoutThread.interrupt()
        stderrThread.interrupt()
        stdoutThread.join(READER_THREAD_JOIN_TIMEOUT_MS)
        stderrThread.join(READER_THREAD_JOIN_TIMEOUT_MS)
        return "TIMEOUT ERROR"
      }

      stdoutThread.join(READER_THREAD_JOIN_TIMEOUT_MS)
      stderrThread.join(READER_THREAD_JOIN_TIMEOUT_MS)

      val stdout = stdoutRef.get()
      val stderr = stderrRef.get()
      val combined = buildString {
        if (stdout.isNotEmpty()) append(stdout)
        if (stderr.isNotEmpty()) {
          if (isNotEmpty()) append("\n")
          append(stderr)
        }
      }

      combined.ifEmpty { "(no output)" }
    } catch (e: Exception) {
      "ERROR: ${e.message}"
    }
  }

  fun executeCommandWithExitCode(command: String, timeoutSeconds: Long = CMD_TIMEOUT_SECONDS): Pair<Int, String> {
    return try {
      val cmd = EnvironmentInstaller.buildShellCommand(context, arrayOf("-c", command))
      val pb = ProcessBuilder(cmd)
        .directory(sandboxRoot)
        .redirectErrorStream(false)

      pb.environment()["HOME"]           = sandboxRoot.absolutePath
      pb.environment()["TMPDIR"]         = EnvironmentInstaller.tmpDir(context).also { it.mkdirs() }.absolutePath
      pb.environment()["LANG"]           = "en_US.UTF-8"
      pb.environment()["SHELL"]          = EnvironmentInstaller.shellPath(context)
      pb.environment()["PATH"]           = "/data/data/com.termux/files/usr/bin:/system/bin"
      pb.environment()["TERM"]           = "xterm-256color"
      
      val prefix2 = EnvironmentInstaller.prefixDir(context)
      if (prefix2.isDirectory) {
        pb.environment()["PREFIX"]         = prefix2.absolutePath
        pb.environment()["TERMUX_PREFIX"]  = prefix2.absolutePath
      }
      pb.environment()["PROOT_TMP_DIR"]    = File(context.filesDir, "tmp").absolutePath
      pb.environment()["PROOT_NO_SECCOMP"] = "1"
      pb.environment()["PROOT_NO_SYSVIPC"] = "1"

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
        try { proc.inputStream.close() } catch (e: Exception) {}
        try { proc.errorStream.close() } catch (e: Exception) {}
        stdoutThread.interrupt()
        stderrThread.interrupt()
        stdoutThread.join(READER_THREAD_JOIN_TIMEOUT_MS)
        stderrThread.join(READER_THREAD_JOIN_TIMEOUT_MS)
        return Pair(-1, "TIMEOUT ERROR")
      }

      stdoutThread.join(READER_THREAD_JOIN_TIMEOUT_MS)
      stderrThread.join(READER_THREAD_JOIN_TIMEOUT_MS)

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

  fun destroySession() = sessionLock.withLock {
    try { stdinWriter?.close() } catch (_: Exception) {}
    process?.destroyForcibly()
    process = null
    stdinWriter = null
    isSessionAlive = false
    ptyBridge.destroySession()
    appendSystemLine("[MSTR_CTRL] Session destroyed.")
  }

  fun clearOutput() {
    _outputLines.value = emptyList()
  }

  fun checkEnvironment(): Boolean {
    val binDir = EnvironmentInstaller.binDir(context)
    if (!binDir.isDirectory) return false

    val checks = listOf("bash --version")
    var needsRepair = false

    for (cmd in checks) {
      val (exitCode, _) = executeCommandWithExitCode(cmd)
      if (exitCode == 127 || exitCode == 13 || exitCode == 126) {
        needsRepair = true
        break
      }
    }

    if (!needsRepair) return true

    executeCommandWithExitCode("chmod -R 700 ${binDir.absolutePath}")
    binDir.walkTopDown().filter { it.isFile }.forEach { file ->
      file.setExecutable(true, false)
      file.setReadable(true, false)
    }

    for (cmd in checks) {
      val (exitCode, _) = executeCommandWithExitCode(cmd)
      if (exitCode == 127 || exitCode == 13 || exitCode == 126) return false
    }

    return true
  }

  fun runPreFlightCheck() {
    if (firstBootHandled) {
      _preFlightReady.value = true
      return
    }

    val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    val alreadyDone = prefs.getBoolean(KEY_FIRST_BOOT_DONE, false)

    if (alreadyDone && EnvironmentInstaller.isInstalled(context)) {
      scope.launch {
        EnvironmentInstaller.ensureInstalled(context)
        if (checkEnvironment()) _terminalOnline.value = true
        firstBootHandled = true
        _preFlightReady.value = true
      }
      return
    }

    scope.launch {
      EnvironmentInstaller.ensureInstalled(context)
      val installerState = EnvironmentInstaller.state.value
      
      if (installerState is EnvironmentInstaller.State.Ready) {
        val pkgBinary = File(EnvironmentInstaller.binDir(context), "pkg")
        
        if (pkgBinary.exists() && pkgBinary.canExecute()) {
          executeCommandWithExitCode("pkg update -y 2>&1", PKG_INSTALL_TIMEOUT_SECONDS)
          executeCommandWithExitCode("pkg upgrade -y && pkg install git python nodejs -y 2>&1", PKG_INSTALL_TIMEOUT_SECONDS)
        }

        val envHealthy = checkEnvironment()
        if (envHealthy) {
          _terminalOnline.value = true
          executeCommandWithExitCode("pip install requests 2>&1", PKG_INSTALL_TIMEOUT_SECONDS)
          executeCommandWithExitCode("git config --global init.defaultBranch main 2>&1")
        }
      }
      
      prefs.edit().putBoolean(KEY_FIRST_BOOT_DONE, true).apply()
      firstBootHandled = true
      _preFlightReady.value = true
    }
  }

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
