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
import com.termux.terminal.TerminalSession
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.File

private const val TAG = "SharedShellManager"

/**
 * Application-level singleton that owns the **single** [TerminalSession] shared by
 * the MSTR_CTRL UI and all AI agents.
 *
 * ## Design goals
 *
 * * **One session, two consumers.** The user interacts with the PTY through the
 *   Termux [com.termux.view.TerminalView] rendered by [com.google.ai.edge.gallery.ui.osmodules.MstrCtrlScreen].
 *   The AI agent injects commands via [injectCommand] and reads the result via
 *   [readScreen].  Both share the exact same [TerminalSession] — there is no
 *   hidden headless copy.
 *
 * * **Lifecycle independence.** [SharedShellManager] is created at the
 *   `GalleryApp` composable level with `remember {}`, which keeps it alive for
 *   the entire Activity lifetime and independent of navigation back-stack
 *   changes.  The PTY session survives screen rotations and MSTR_CTRL
 *   visibility toggles.
 *
 * * **UI hook.** [MstrCtrlScreen] calls [bridge] to attach [com.termux.view.TerminalView]
 *   and overrides the session callback to drive `TerminalView.onScreenUpdated()`.
 *   It must NOT call [bridge].destroySession() on disposal — just detach
 *   the view.
 *
 * * **AI hook.** Any agent (e.g. [com.google.ai.edge.gallery.customtasks.agentchat.AgentTools])
 *   holds a reference to this manager and calls:
 *   ```kotlin
 *   sharedShellManager.injectCommand("ls -la")   // write to shared PTY
 *   Thread.sleep(800)                             // wait for output
 *   val output = sharedShellManager.readScreen()  // snapshot current screen
 *   ```
 *   The command appears in the MSTR_CTRL terminal in real time, giving the
 *   user full observability of AI-driven actions.
 */
class SharedShellManager(context: Context) {

  /** The PTY-backed bridge.  Exposed so [MstrCtrlScreen] can call [TermuxSessionBridge.setCallback]. */
  val bridge: TermuxSessionBridge = TermuxSessionBridge(context)

  /** Working directory — also used as `$HOME` inside the shell. */
  val sandboxRoot: File = File(context.filesDir, "clu_file_box").also { it.mkdirs() }

  // ── Observable screen-change counter ─────────────────────────
  // Incremented each time the PTY emits new output.  The UI observes
  // this to invalidate TerminalView; the AI polls or awaits it to know
  // when output has settled.
  private val _screenVersion = MutableStateFlow(0)

  /**
   * Monotonically increasing counter bumped on every [TermuxSessionBridge.Callback.onTextChanged]
   * event.  Collect this in the UI to trigger `TerminalView.onScreenUpdated()`, or
   * compare values in the AI layer to detect when output has stabilised.
   */
  val screenVersion: StateFlow<Int> = _screenVersion.asStateFlow()

  // ── Session-terminated status ─────────────────────────────────
  private val _sessionExitCode = MutableStateFlow<Int?>(null)

  /** Emits the exit code when the shell process terminates; `null` while alive. */
  val sessionExitCode: StateFlow<Int?> = _sessionExitCode.asStateFlow()

  init {
    // Wire a default callback so screen-version tracking starts immediately.
    // MstrCtrlScreen will override this callback (via bridge.setCallback) to
    // also drive TerminalView invalidation — that override is fine because
    // the new callback will still call _screenVersion.update internally via
    // the bridge's public API if the screen needs it.  We keep this init
    // callback as a no-op-safe default for headless / AI-only contexts.
    bridge.setCallback(object : TermuxSessionBridge.Callback {
      override fun onTextChanged() {
        _screenVersion.update { it + 1 }
      }
      override fun onTitleChanged(title: String) {}
      override fun onBell() {}
      override fun onSessionFinished(exitCode: Int) {
        _sessionExitCode.value = exitCode
        Log.d(TAG, "Shared session finished with exit code $exitCode")
      }
    })

    // Start the single shared PTY session.
    bridge.createSession(sandboxRoot)
    Log.d(TAG, "SharedShellManager initialised — sandboxRoot=${sandboxRoot.absolutePath}")
  }

  // ── AI agent API ─────────────────────────────────────────────

  /**
   * Injects [command] into the shared PTY, exactly as if the user had typed
   * it in the MSTR_CTRL terminal and pressed Enter.
   *
   * The command and its output appear in the live MSTR_CTRL UI in real time,
   * giving the user full visibility of AI-driven operations.
   *
   * @param command Shell command string (no trailing newline required).
   */
  fun injectCommand(command: String) {
    Log.d(TAG, "AI injectCommand: $command")
    bridge.sendCommand(command)
    _screenVersion.update { it + 1 }
  }

  /**
   * Returns a plain-text snapshot of the terminal's current visible screen
   * (scrollback + active rows).
   *
   * Typical AI usage pattern:
   * ```kotlin
   * sharedShellManager.injectCommand("python3 script.py")
   * Thread.sleep(1_200)   // allow output to arrive
   * val result = sharedShellManager.readScreen()
   * ```
   *
   * Screen dimensions default to 80×24 until [com.termux.view.TerminalView]
   * calls `updateSize()`.  The snapshot is therefore most useful **after**
   * the MSTR_CTRL screen has been displayed at least once.
   *
   * @return All visible terminal rows joined by newlines, trailing whitespace
   *         stripped per row.  Returns an empty string when the session has not
   *         yet started or the emulator is not initialised.
   */
  fun readScreen(): String {
    val session: TerminalSession = bridge.terminalSession ?: return ""
    val emulator = session.emulator ?: return ""
    val screen = emulator.screen
    val transcriptRows = screen.activeTranscriptRows
    val visibleRows = screen.mRows
    val cols = screen.mColumns
    if (cols <= 0 || visibleRows <= 0) return ""
    // getSelectedText covers scrollback (negative row indices) + visible rows.
    return screen.getSelectedText(
      /* selX1 = */ 0,
      /* selY1 = */ -transcriptRows,
      /* selX2 = */ cols - 1,
      /* selY2 = */ visibleRows - 1,
    ).trimEnd()
  }

  /**
   * Sends Ctrl-C to the running foreground process (SIGINT), interrupting
   * any command currently executing in the shared shell.
   */
  fun sendInterrupt() {
    bridge.write("\u0003") // ETX = Ctrl-C
  }

  // ── Session health ────────────────────────────────────────────

  /** `true` while the underlying shell process is alive. */
  val isAlive: Boolean get() = bridge.isAlive

  /**
   * Destroys the current session and immediately starts a fresh one.
   * Both the UI and AI will see the new session on the next interaction.
   */
  fun restartSession() {
    bridge.destroySession()
    _sessionExitCode.value = null
    bridge.createSession(sandboxRoot)
    Log.d(TAG, "Shared session restarted")
  }
}
