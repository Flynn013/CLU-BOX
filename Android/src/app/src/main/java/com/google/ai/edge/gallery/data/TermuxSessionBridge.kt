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

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.util.Log
import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import java.io.File

private const val TAG = "TermuxSessionBridge"

/**
 * Wraps the Termux [TerminalSession] to provide a PTY-backed shell inside
 * the CLU-BOX sandbox.
 *
 * A PTY (pseudo-terminal) gives the shell a real terminal device, so:
 * - ANSI escape codes (colours, cursor movement) work correctly.
 * - Interactive tools (vim, less, top) work.
 * - Job control (Ctrl-C, Ctrl-Z) works.
 * - No separate stdout/stderr demux is needed — everything flows through the PTY.
 *
 * The bridge is created lazily by [TerminalSessionManager] and destroyed when
 * the session ends.
 */
class TermuxSessionBridge(private val context: Context) {

  /** The underlying Termux terminal session. `null` until [createSession]. */
  var terminalSession: TerminalSession? = null
    private set

  /** Callback interface for the UI to receive terminal updates. */
  interface Callback {
    /** Called when new text arrives in the terminal. */
    fun onTextChanged()
    /** Called when the terminal title changes (e.g. from shell prompt). */
    fun onTitleChanged(title: String)
    /** Called when a bell character is received. */
    fun onBell()
    /** Called when the session finishes. */
    fun onSessionFinished()
  }

  private var callback: Callback? = null

  fun setCallback(cb: Callback) {
    callback = cb
  }

  /**
   * The [TerminalSessionClient] implementation wired into every
   * [TerminalSession] created by this bridge.
   */
  val sessionClient: TerminalSessionClient = object : TerminalSessionClient {

    override fun onTextChanged(changedSession: TerminalSession) {
      callback?.onTextChanged()
    }

    override fun onTitleChanged(changedSession: TerminalSession) {
      callback?.onTitleChanged(changedSession.title ?: "")
    }

    override fun onSessionFinished(finishedSession: TerminalSession) {
      Log.d(TAG, "Terminal session finished")
      callback?.onSessionFinished()
    }

    override fun onBell(session: TerminalSession) {
      callback?.onBell()
    }

    override fun onColorsChanged(session: TerminalSession) {
      // Colour palette update — UI will pick up on next render.
    }

    override fun onTerminalCursorStateChange(state: Boolean) {
      // Cursor blink state changed — no special handling needed.
    }

    override fun getTerminalCursorStyle(): Int? {
      // Return null to use the default cursor style.
      return null
    }

    override fun onCopyTextToClipboard(session: TerminalSession, text: String?) {
      if (text != null) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE)
            as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Terminal", text))
      }
    }

    override fun onPasteTextFromClipboard(session: TerminalSession?) {
      val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE)
          as ClipboardManager
      val clip = clipboard.primaryClip
      if (clip != null && clip.itemCount > 0) {
        val text = clip.getItemAt(0).coerceToText(context).toString()
        session?.write(text)
      }
    }

    override fun logError(tag: String?, message: String?) {
      Log.e(tag ?: TAG, message ?: "(null)")
    }

    override fun logWarn(tag: String?, message: String?) {
      Log.w(tag ?: TAG, message ?: "(null)")
    }

    override fun logInfo(tag: String?, message: String?) {
      Log.i(tag ?: TAG, message ?: "(null)")
    }

    override fun logDebug(tag: String?, message: String?) {
      Log.d(tag ?: TAG, message ?: "(null)")
    }

    override fun logVerbose(tag: String?, message: String?) {
      Log.v(tag ?: TAG, message ?: "(null)")
    }

    override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) {
      Log.e(tag ?: TAG, message, e)
    }

    override fun logStackTrace(tag: String?, e: Exception?) {
      Log.e(tag ?: TAG, "Stack trace", e)
    }
  }

  /** `true` when the last [createSession] attempt failed due to a native/security error. */
  @Volatile
  var sessionInitFailed: Boolean = false
    private set

  /** Human-readable reason when [sessionInitFailed] is `true`. */
  @Volatile
  var sessionInitError: String? = null
    private set

  /**
   * Creates and starts a new PTY-backed shell session.
   *
   * The shell process is not started until the [com.termux.view.TerminalView]
   * calls [TerminalSession.updateSize] during layout.
   *
   * All native/JNI work is wrapped in a blast-shield `try-catch` so that
   * a W^X security denial, missing native lib, or SELinux block cannot
   * crash the entire application. On failure, [sessionInitFailed] is set
   * and a diagnostic message is stored in [sessionInitError].
   *
   * @param sandboxRoot The directory to use as `$HOME` and initial cwd.
   * @param shell       Path to the shell binary (default: `/system/bin/sh`).
   */
  fun createSession(sandboxRoot: File, shell: String = EnvironmentInstaller.shellPath(context)) {
    sessionInitFailed = false
    sessionInitError = null

    try {
      Log.d(TAG, "Checkpoint 1: Preparing session (shell=$shell)")

      if (terminalSession != null) {
        Log.w(TAG, "Session already exists — destroying old one first")
        destroySession()
      }

      val cwd = sandboxRoot.absolutePath
      val home = sandboxRoot.absolutePath

      Log.d(TAG, "Checkpoint 2: Building environment")

      // Build the environment for the shell using the internal sysroot.
      val binDir = EnvironmentInstaller.binDir(context)
      val prefix = EnvironmentInstaller.prefixDir(context)
      val basePath = "/system/bin:/system/xbin"
      val effectivePath = if (binDir.isDirectory) "${binDir.absolutePath}:$basePath" else basePath

      val env = mutableListOf(
        "HOME=$home",
        "TERM=xterm-256color",
        "PATH=$effectivePath",
        "COLORTERM=truecolor",
        "LANG=en_US.UTF-8",
        "TMPDIR=${context.cacheDir.absolutePath}",
        // Visible prompt so the user gets immediate boot feedback.
        "PS1=CLU/BOX \$ ",
      )
      // Inject sysroot environment variables when the bootstrap prefix exists,
      // giving the PTY shell access to bash, python, node, pkg, git, and native
      // shared libraries.
      if (prefix.isDirectory) {
        env.add("PREFIX=${prefix.absolutePath}")
        env.add("LD_LIBRARY_PATH=${EnvironmentInstaller.libDir(context).absolutePath}")
      }

      val args = arrayOf(shell)

      Log.d(TAG, "Checkpoint 3: Allocating PTY via TerminalSession")

      terminalSession = TerminalSession(
        shell,    // executable
        cwd,      // working directory
        args,     // arguments
        env.toTypedArray(),  // environment
        // Use the Termux library's default transcript size (typically 2000 rows).
        // This provides a reasonable scrollback buffer for CLU-BOX workflows.
        TerminalEmulator.DEFAULT_TERMINAL_TRANSCRIPT_ROWS,
        sessionClient,
      )

      Log.i(TAG, "Checkpoint 4: PTY session created — waiting for TerminalView layout to start shell")
    } catch (e: SecurityException) {
      // W^X policy or SELinux denial — the OS blocked PTY allocation.
      val msg = "Terminal blocked by Android security (W^X / SELinux): ${e.message}"
      Log.e(TAG, msg, e)
      sessionInitFailed = true
      sessionInitError = msg
    } catch (e: UnsatisfiedLinkError) {
      // Missing or incompatible native library (JNI crash).
      val msg = "Terminal native library load failed: ${e.message}"
      Log.e(TAG, msg, e)
      sessionInitFailed = true
      sessionInitError = msg
    } catch (e: Throwable) {
      // Catch-all blast shield — prevents ANY native crash from killing the app.
      val msg = "Terminal init failed (${e.javaClass.simpleName}): ${e.message}"
      Log.e(TAG, msg, e)
      sessionInitFailed = true
      sessionInitError = msg
    }
  }

  /**
   * Writes [text] into the terminal's stdin (PTY master side).
   * Use this to send commands or user keystrokes.
   */
  fun write(text: String) {
    terminalSession?.write(text)
  }

  /**
   * Sends a command followed by a newline (Enter key).
   */
  fun sendCommand(command: String) {
    write("$command\n")
  }

  /** True if a session exists and its process is still alive. */
  val isAlive: Boolean
    get() = terminalSession?.isRunning == true

  /** Destroys the current session, killing the shell process. */
  fun destroySession() {
    terminalSession?.finishIfRunning()
    terminalSession = null
    Log.d(TAG, "Session destroyed")
  }
}
