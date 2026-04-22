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

package com.google.ai.edge.gallery.ui.osmodules

import android.content.Context
import android.graphics.Typeface
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.inputmethod.InputMethodManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.google.ai.edge.gallery.data.EnvironmentInstaller
import com.google.ai.edge.gallery.data.SharedShellManager
import com.google.ai.edge.gallery.data.TerminalSessionManager
import com.google.ai.edge.gallery.data.TermuxSessionBridge
import com.google.ai.edge.gallery.ui.theme.absoluteBlack
import com.google.ai.edge.gallery.ui.theme.neonGreen
import com.termux.terminal.TerminalSession
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * MSTR_CTRL — full-screen terminal UI powered by the Termux terminal-emulator
 * library.
 *
 * The screen embeds the native [TerminalView] via [AndroidView], giving the
 * user a real PTY-backed shell with ANSI colour support, interactive programs,
 * and job control.
 *
 * The [TerminalSessionManager] is still started for:
 * - The pre-flight firmware check (first-boot detection).
 * - Agent-initiated `Shell_Execute` / `executeCommandWithExitCode` calls
 *   (which use isolated one-shot `sh -c` processes for timeout safety).
 *
 * UI rules:
 * • Pure black background, neon green text.
 * • Full-screen TerminalView for interactive shell.
 * • Bottom input row for quick command injection.
 */
@Composable
fun MstrCtrlScreen(
  sessionManager: TerminalSessionManager,
  sharedShellManager: SharedShellManager,
) {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  var inputText by remember { mutableStateOf("") }

  // ── Bootstrap state observation ──────────────────────────────
  // Observe the installer state so the progress overlay and the terminal
  // can react once the bootstrap finishes.  The actual installation is
  // driven by TerminalSessionManager.startSession() → runPreFlightCheck()
  // → EnvironmentInstaller.ensureInstalled().  Do NOT call ensureInstalled
  // here: a second concurrent call races the first and they both write to
  // the same cacheDir zip file, corrupting the archive and causing
  // extraction to fail with an invalid-zip error.
  val bootstrapState by EnvironmentInstaller.state.collectAsState()

  // ── Terminal online status (set after pkg update -y succeeds) ──
  val terminalOnline by sessionManager.terminalOnline.collectAsState()

  // Epoch counter incremented on every bridge restart to force the
  // AndroidView to fully re-create and re-attach the new PTY session.
  var bridgeRestartCount by remember { mutableStateOf(0) }

  // Message shown at the bottom of the terminal when the PTY session exits.
  // Null while the session is alive; set to the exit-code banner on death.
  var sessionTerminatedMsg by remember { mutableStateOf<String?>(null) }

  // If the bootstrap is not yet ready (including failed — user gets a retry button
  // instead of a silent degraded /system/bin/sh terminal), show the overlay.
  if (bootstrapState !is EnvironmentInstaller.State.Ready) {
    BootstrapProgressOverlay(
      state = bootstrapState,
      onRetry = if (bootstrapState is EnvironmentInstaller.State.Failed) {
        { scope.launch { EnvironmentInstaller.retry(context) } }
      } else null,
    )
    return
  }

  // Hold a reference to the TerminalView so we can invalidate it
  // when new output arrives from the PTY.
  var terminalViewRef by remember { mutableStateOf<TerminalView?>(null) }

  // Use the application-level singleton bridge from SharedShellManager.
  // This keeps the PTY session alive when the user switches to another module
  // and returns — no shell restart, no lost history.
  // The session was already created in SharedShellManager.init; we only
  // attach/detach the TerminalView here.
  val bridge = sharedShellManager.bridge
  if (bridge.sessionInitFailed) {
    Log.e("MstrCtrlScreen", "PTY init failed: ${bridge.sessionInitError}")
  }

  // Start the legacy session manager (pre-flight + agent tools).
  LaunchedEffect(Unit) {
    sessionManager.startSession()
  }

  // Wire up the bridge callback to invalidate the TerminalView whenever
  // new text arrives from the PTY.  Re-wires when the view ref changes.
  // Note: setCallback replaces the default SharedShellManager callback, so
  // we also call sharedShellManager.bridge's internal screen-version bump.
  LaunchedEffect(terminalViewRef) {
    bridge.setCallback(object : TermuxSessionBridge.Callback {
      override fun onTextChanged() {
        // onScreenUpdated() handles scroll tracking and triggers invalidate().
        // Post to the UI thread since this callback fires from the PTY reader.
        // Capture in a local to avoid a race between the null-check and post.
        val view = terminalViewRef ?: return
        view.post { view.onScreenUpdated() }
      }

      override fun onTitleChanged(title: String) {}
      override fun onBell() {}
      override fun onSessionFinished(exitCode: Int) {
        // Leave the terminal view open so the developer can read the preceding
        // error output.  Restarting here would wipe the crash log.
        sessionTerminatedMsg = "[SESSION TERMINATED - Exit Code: $exitCode]"
      }
    })
  }

  // Auto-upgrade: when the bootstrap self-heals and the terminal goes ONLINE,
  // restart the bridge if it was started with the /system/bin/sh fallback.
  // This replaces the stuck /system/bin/sh session with a proper bash session
  // without requiring the user to manually press the restart button.
  LaunchedEffect(terminalOnline) {
    if (terminalOnline && bridge.usedFallbackShell) {
      withContext(Dispatchers.IO) {
        sharedShellManager.restartSession()
      }
      bridgeRestartCount++ // Triggers AndroidView re-attachment with the new bash session.
    }
  }

  Column(
    modifier = Modifier
      .fillMaxSize()
      .background(absoluteBlack)
      .imePadding(),
  ) {
    // ── Status bar: TERMINAL ONLINE indicator ──────────────────
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .background(absoluteBlack)
        .padding(horizontal = 10.dp, vertical = 3.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Text(
        text = if (terminalOnline) "● TERMINAL: ONLINE" else "○ TERMINAL: OFFLINE",
        color = if (terminalOnline) neonGreen else neonGreen.copy(alpha = 0.4f),
        fontFamily = FontFamily.Monospace,
        fontSize = 12.sp,
      )
    }
    // ── Termux TerminalView (main content area) ────────────────
    Box(
      modifier = Modifier
        .weight(1f)
        .fillMaxWidth(),
    ) {
      // key(bridgeRestartCount) forces a full TerminalView re-creation whenever the
      // bridge is restarted (e.g. after self-healing upgrades /system/bin/sh
      // to bash).  This ensures attachSession() runs against the new session.
      key(bridgeRestartCount) {
        AndroidView(
          modifier = Modifier.fillMaxSize(),
          factory = { ctx ->
            TerminalView(ctx, null).apply {
              // Wire up the view client that handles keyboard/touch events.
              setTerminalViewClient(CluTerminalViewClient(this))

              // Style: monospace font, neon-green on black.
              setTextSize(20)
              setTypeface(Typeface.MONOSPACE)

              // Black background to match CLU/BOX aesthetic.
              setBackgroundColor(android.graphics.Color.BLACK)

              // Make the view focusable so it can receive keyboard input.
              isFocusable = true
              isFocusableInTouchMode = true

              // Attach the PTY session — the shell starts once the view measures.
              val session = bridge.terminalSession
              if (session != null) {
                attachSession(session)
              }

              // Store reference so the bridge callback can invalidate this view.
              terminalViewRef = this
            }
          },
          update = { view ->
            // Keep the view reference current.
            terminalViewRef = view
            // Re-attach if the session was recreated.
            // Note: `mTermSession` is a public field in Termux's TerminalView Java API.
            // There is no getter method — direct access is the intended usage pattern.
            val session = bridge.terminalSession
            if (session != null && view.mTermSession !== session) {
              view.attachSession(session)
            }
          },
        )
      }
    }

    // ── Session-terminated banner ──────────────────────────────
    // Shown when the PTY process exits, so the developer can see the exit
    // code without the screen disappearing.  Cleared when the session is
    // manually restarted via the restart button.
    val terminationMsg = sessionTerminatedMsg
    if (terminationMsg != null) {
      Row(
        modifier = Modifier
          .fillMaxWidth()
          .background(absoluteBlack)
          .padding(horizontal = 10.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Text(
          text = terminationMsg,
          color = neonGreen.copy(alpha = 0.7f),
          fontFamily = FontFamily.Monospace,
          fontSize = 12.sp,
        )
      }
    }

    // ── Bottom input row (quick command injection) ─────────────
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .background(absoluteBlack)
        .padding(horizontal = 8.dp, vertical = 6.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Text(
        "$",
        color = neonGreen,
        fontFamily = FontFamily.Monospace,
        fontSize = 18.sp,
        modifier = Modifier.padding(end = 6.dp),
      )

      BasicTextField(
        value = inputText,
        onValueChange = { inputText = it },
        modifier = Modifier
          .weight(1f)
          .padding(vertical = 4.dp),
        textStyle = TextStyle(
          fontFamily = FontFamily.Monospace,
          fontSize = 18.sp,
          color = neonGreen,
        ),
        cursorBrush = SolidColor(neonGreen),
        singleLine = true,
      )

      Spacer(Modifier.width(4.dp))

      // Send button — injects command into the PTY.
      IconButton(
        onClick = {
          val cmd = inputText.trim()
          if (cmd.isNotEmpty()) {
            inputText = ""
            bridge.sendCommand(cmd)
          }
        },
      ) {
        Icon(
          Icons.AutoMirrored.Filled.Send,
          contentDescription = "Execute",
          tint = neonGreen,
        )
      }

      // Clear / restart session — delegates to SharedShellManager so the
      // single PTY session is replaced and both UI and AI see the new one.
      IconButton(
        onClick = {
          sessionTerminatedMsg = null
          scope.launch(Dispatchers.IO) {
            sharedShellManager.restartSession()
          }
        },
      ) {
        Icon(
          Icons.Default.DeleteSweep,
          contentDescription = "Restart terminal",
          tint = neonGreen.copy(alpha = 0.6f),
        )
      }
    }
  }
}

// ── Bootstrap progress overlay ───────────────────────────────────────────
//
// Shown while EnvironmentInstaller is downloading/extracting the sysroot,
// AND when the install fails — so the user always gets a clear status message
// and a Retry button instead of a silent degraded /system/bin/sh terminal.

@Composable
private fun BootstrapProgressOverlay(
  state: EnvironmentInstaller.State,
  onRetry: (() -> Unit)? = null,
) {
  Box(
    modifier = Modifier
      .fillMaxSize()
      .background(absoluteBlack),
    contentAlignment = Alignment.Center,
  ) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
      val message = when (state) {
        is EnvironmentInstaller.State.Idle -> "Preparing Linux environment…"
        is EnvironmentInstaller.State.Downloading ->
          "Downloading bootstrap (${state.percent}%)…\nThis only happens once."
        is EnvironmentInstaller.State.Extracting -> "Extracting sysroot…"
        is EnvironmentInstaller.State.FixingPermissions -> "Setting up environment…"
        is EnvironmentInstaller.State.Failed ->
          "Bootstrap failed:\n${state.message}\n\nTap Retry to try again."
        is EnvironmentInstaller.State.Ready -> "Ready!"
      }

      Text(
        text = message,
        color = if (state is EnvironmentInstaller.State.Failed) neonGreen.copy(alpha = 0.8f) else neonGreen,
        fontFamily = FontFamily.Monospace,
        fontSize = 16.sp,
        textAlign = TextAlign.Center,
        modifier = Modifier.padding(32.dp),
      )

      // Show Retry button only when failed and a retry callback is provided.
      if (state is EnvironmentInstaller.State.Failed && onRetry != null) {
        Spacer(Modifier.height(16.dp))
        Button(
          onClick = onRetry,
          colors = ButtonDefaults.buttonColors(
            containerColor = neonGreen.copy(alpha = 0.15f),
            contentColor = neonGreen,
          ),
        ) {
          Icon(Icons.Default.Refresh, contentDescription = null)
          Spacer(Modifier.width(8.dp))
          Text(
            text = "RETRY INSTALL",
            fontFamily = FontFamily.Monospace,
            fontSize = 15.sp,
          )
        }
      }
    }
  }
}

// ── TerminalViewClient implementation ────────────────────────────────────
//
// Handles keyboard, touch, and IME events for the embedded TerminalView.

private class CluTerminalViewClient(
  private val terminalView: TerminalView,
) : TerminalViewClient {

  override fun onScale(scale: Float): Float = 1.0f // Disable pinch-to-zoom.

  override fun onSingleTapUp(e: MotionEvent) {
    // Request focus and show the soft keyboard so the user can type.
    terminalView.requestFocus()
    val imm = terminalView.context.getSystemService(Context.INPUT_METHOD_SERVICE)
        as InputMethodManager
    imm.showSoftInput(terminalView, InputMethodManager.SHOW_IMPLICIT)
  }

  override fun shouldBackButtonBeMappedToEscape(): Boolean = false

  override fun shouldEnforceCharBasedInput(): Boolean = true

  override fun shouldUseCtrlSpaceWorkaround(): Boolean = false

  override fun isTerminalViewSelected(): Boolean = true

  override fun copyModeChanged(copyMode: Boolean) {
    // No-op — copy mode UI not needed in CLU/BOX.
  }

  override fun onKeyDown(keyCode: Int, e: KeyEvent, session: TerminalSession): Boolean = false

  override fun onKeyUp(keyCode: Int, e: KeyEvent): Boolean = false

  override fun onLongPress(event: MotionEvent): Boolean = false

  override fun readControlKey(): Boolean = false

  override fun readAltKey(): Boolean = false

  override fun readShiftKey(): Boolean = false

  override fun readFnKey(): Boolean = false

  override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession): Boolean =
    false

  override fun onEmulatorSet() {
    // Terminal emulator initialized — the shell is about to start.
  }

  override fun logError(tag: String?, message: String?) {}
  override fun logWarn(tag: String?, message: String?) {}
  override fun logInfo(tag: String?, message: String?) {}
  override fun logDebug(tag: String?, message: String?) {}
  override fun logVerbose(tag: String?, message: String?) {}
  override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) {}
  override fun logStackTrace(tag: String?, e: Exception?) {}
}
