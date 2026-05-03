/*
 * Copyright 2026 Flynn013 / CLU/BOX
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * ─────────────────────────────────────────────────────────────────────────
 *  COMPATIBILITY SHIM LAYER
 *
 *  The Cognitive-OS refactor purges the old Termux/PRoot stack but several
 *  legacy call-sites (MstrCtrlScreen, AgentTools, ScdlBoxWorker, FileBoxScreen,
 *  DiffBoxScreen, GalleryApp, …) still reference the old class names.
 *
 *  Rather than scatter ad-hoc replacements across the codebase, this single
 *  file resurrects the seven removed types as **BusyBox-backed thin wrappers**:
 *
 *    EnvironmentInstaller    → no-op state machine (Ready immediately)
 *    NativeShellBridge       → BusyBoxBridge.shell() facade with diagnose()
 *    SharedShellManager      → singleton holding a TermuxSessionBridge stub
 *    TerminalSessionManager  → executeCommandWithExitCode() via BusyBoxBridge
 *    TermuxSessionBridge     → minimal Callback-aware shim
 *    VirtualCommandResult    → result data carrier (kept for source compat)
 *    VirtualCommandResultBus → empty Flow (no IPC required anymore)
 *
 *  All seven shims delegate real work to [BusyBoxBridge] and present the
 *  exact public surface the old call-sites expect, so no other Kotlin file
 *  needs to change.
 * ─────────────────────────────────────────────────────────────────────────
 */

package com.google.ai.edge.gallery.data

import android.content.Context
import android.util.Log
import com.google.ai.edge.gallery.data.busybox.BusyBoxBridge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

private const val SHIM_TAG = "CluCompatShim"

// ─────────────────────────────────────────────────────────────────────────
//  EnvironmentInstaller — no-op state machine
// ─────────────────────────────────────────────────────────────────────────

/**
 * Backwards-compat wrapper that immediately reports [State.Ready].
 *
 * The Cognitive-OS no longer downloads a sysroot — BusyBox is bundled as an
 * APK asset and extracted lazily by [BusyBoxBridge.ensureInstalled].
 */
object EnvironmentInstaller {

  sealed class State {
    object Idle : State()
    data class Downloading(val percent: Int = 0) : State()
    object Extracting : State()
    object FixingPermissions : State()
    data class Failed(val message: String) : State()
    object Ready : State()
  }

  private val _state = MutableStateFlow<State>(State.Ready)
  val state: StateFlow<State> = _state.asStateFlow()

  /** Idempotent. Always succeeds because BusyBox is an in-APK asset. */
  suspend fun ensureInstalled(context: Context) {
    val ok = BusyBoxBridge.ensureInstalled(context) != null
    _state.value = if (ok) State.Ready else State.Failed("busybox-arm64-v8a asset missing")
  }

  /** Re-run the (no-op) install. */
  suspend fun retry(context: Context) {
    _state.value = State.Idle
    ensureInstalled(context)
  }
}

// ─────────────────────────────────────────────────────────────────────────
//  NativeShellBridge — BusyBoxBridge facade
// ─────────────────────────────────────────────────────────────────────────

/**
 * Thin facade over [BusyBoxBridge] preserving the legacy
 * `NativeShellBridge.diagnose()` API used by [logbox][com.google.ai.edge.gallery.data.LogBoxManager]
 * and the MstrCtrl pre-flight banner.
 */
object NativeShellBridge {
  /** Multi-line diagnostic blob shown in the logcat header / system settings. */
  fun diagnose(context: Context): String = runBlocking {
    val bin = BusyBoxBridge.binaryPath(context)
    val installed = BusyBoxBridge.isInstalled(context)
    val version = if (installed) {
      val r = BusyBoxBridge.exec(context, "uname", listOf("-a"))
      r.stdout.trim().ifBlank { r.stderr.trim() }
    } else "(busybox not installed)"
    """
      [NativeShellBridge ⇒ BusyBoxBridge]
      binary    : ${bin.absolutePath}
      installed : $installed
      uname     : $version
      workdir   : ${BusyBoxBridge.defaultWorkDir(context).absolutePath}
    """.trimIndent()
  }
}

// ─────────────────────────────────────────────────────────────────────────
//  TermuxSessionBridge — minimal callback-aware stub
// ─────────────────────────────────────────────────────────────────────────

/**
 * Source-compat replacement for the deleted `TermuxSessionBridge`.
 *
 * MstrCtrl uses this class to wire a [Callback] into the legacy PTY pipeline.
 * The new pure-native MSTR_CTRL UI uses a Compose-native scrolling buffer
 * instead of `com.termux.view.TerminalView`, but we keep this type alive so
 * unrelated callers (e.g. saved sessions, custom listeners) still compile.
 */
class TermuxSessionBridge(@Suppress("UNUSED_PARAMETER") context: Context) {
  /** The legacy Callback interface — only `onTextChanged` is actually fired. */
  interface Callback {
    fun onTextChanged()
    fun onTitleChanged(title: String)
    fun onBell()
    fun onSessionFinished(exitCode: Int)
  }

  /** Internal storage for the registered callback. */
  private var callback: Callback? = null

  /** Mirrors the old API surface used by MstrCtrlScreen/SharedShellManager. */
  var sessionInitFailed: Boolean = false
  var sessionInitError: String? = null
  var usedFallbackShell: Boolean = false

  /** Register a [Callback] (replaces any previous one). */
  fun setCallback(cb: Callback) {
    this.callback = cb
  }

  /** Fire the registered `onTextChanged` callback (used internally on output append). */
  fun notifyTextChanged() {
    callback?.onTextChanged()
  }
}

// ─────────────────────────────────────────────────────────────────────────
//  SharedShellManager — application-scoped singleton holder
// ─────────────────────────────────────────────────────────────────────────

/**
 * Source-compat singleton previously holding the global PTY session.
 *
 * The new MSTR_CTRL screen does not need a shared session, but several
 * call-sites still pass `SharedShellManager` references around. We expose
 * the same [bridge] / [restartSession] API so they continue to compile.
 */
class SharedShellManager(private val context: Context) {
  val bridge: TermuxSessionBridge = TermuxSessionBridge(context)

  /**
   * No-op restart. The new BusyBox bridge spawns a fresh sub-process per
   * command, so there is no long-running session to restart.
   */
  fun restartSession() {
    Log.d(SHIM_TAG, "SharedShellManager.restartSession() — no-op under BusyBoxBridge")
  }
}

// ─────────────────────────────────────────────────────────────────────────
//  TerminalSessionManager — direct command-execution facade
// ─────────────────────────────────────────────────────────────────────────

/**
 * Source-compat replacement for the deleted `TerminalSessionManager`.
 *
 * Provides the synchronous `executeCommandWithExitCode()` API used by
 * [com.google.ai.edge.gallery.customtasks.agentchat.AgentTools.shellExecute]
 * and the SCDL_BOX worker.
 */
class TerminalSessionManager(private val context: Context) {

  /**
   * Mirrors the old `terminalOnline` Flow. We start in the `true` state
   * because the BusyBox sub-process model has no notion of a long-running
   * connection that can drop offline.
   */
  private val _terminalOnline = MutableStateFlow(true)
  val terminalOnline: StateFlow<Boolean> = _terminalOnline.asStateFlow()

  /** Fire-and-forget pre-flight; ensures BusyBox is extracted. */
  suspend fun startSession() {
    BusyBoxBridge.ensureInstalled(context)
    _terminalOnline.value = BusyBoxBridge.isInstalled(context)
  }

  /**
   * Execute [command] and return `(exitCode, mergedOutput)`.
   *
   * Internally evaluates the command through `busybox sh -c …` so full pipes,
   * redirections and substitutions are supported.
   */
  fun executeCommandWithExitCode(command: String): Pair<Int, String> = runBlocking {
    val r = BusyBoxBridge.shell(context, command)
    val merged = buildString {
      append(r.stdout)
      if (r.stderr.isNotBlank()) {
        if (isNotEmpty()) append('\n')
        append("[stderr] ${r.stderr}")
      }
    }
    r.exitCode to merged
  }
}

// ─────────────────────────────────────────────────────────────────────────
//  VirtualCommandResult / Bus — kept for source-compat
// ─────────────────────────────────────────────────────────────────────────

/**
 * Source-compat data class for callers that still import the type. New code
 * should use [BusyBoxBridge.Result] directly.
 */
data class VirtualCommandResult(
  val correlationId: String,
  val exitCode: Int,
  val stdout: String,
  val stderr: String,
)

/**
 * Source-compat empty event bus. The previous Termux IPC pipeline has been
 * removed, so this object exposes a permanently-empty [SharedFlow].
 */
object VirtualCommandResultBus {
  private val _resultFlow = MutableSharedFlow<VirtualCommandResult>(extraBufferCapacity = 1)
  val resultFlow: SharedFlow<VirtualCommandResult> = _resultFlow.asSharedFlow()

  /** Convenience: emit a result onto the bus from a [CoroutineScope]. */
  fun emit(scope: CoroutineScope, result: VirtualCommandResult) {
    scope.launch(Dispatchers.IO) { _resultFlow.emit(result) }
  }
}
