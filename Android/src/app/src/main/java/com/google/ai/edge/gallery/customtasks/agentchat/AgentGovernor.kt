/*
 * Copyright 2026 Flynn013 / CLU/BOX
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package com.google.ai.edge.gallery.customtasks.agentchat

import android.util.Log
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicInteger

private const val TAG = "AgentGovernor"

/**
 * Goose-style hybrid orchestration governor for the CLU/BOX autonomous loop.
 *
 * Drives the agent's
 * `Stream Tokens → Detect Tool Call → Halt Stream → Route to Skill Registry →
 *  Execute → Append Typed Result → Resume` cycle, identical in shape to the
 * loop that powers Block's `block/goose` desktop client.
 *
 * # Engine routing
 * Hybrid routing is a *first-class* concern. The governor does not own the
 * inference engines themselves; instead it stores the active [AgentEngine]
 * and exposes [shouldRouteToCloud] / [shouldRouteToLocal] so the loop driver
 * (`AgentChatScreen`) can dispatch token streams to either the Gemini Cloud
 * endpoint or the local LiteRT model based on the active configuration —
 * **without any changes to the protected
 * `com.google.ai.edge.gallery.runtime`** package.
 *
 * # State machine
 * ```
 *  IDLE → STREAMING_TOKENS → TOOL_CALL_DETECTED → EXECUTING_TOOL → RESUMING_STREAM → STREAMING_TOKENS …
 *                                                              │
 *                                                              ▼
 *                                                          ERROR / EXHAUSTED
 * ```
 *
 * Transitions are emitted via [phase] (a [StateFlow]) so any number of
 * Compose collectors can render Goose-style collapsible tool-execution boxes
 * in real time.
 *
 * # Backwards compatibility
 * The original `AgentGovernor(maxLoops)` constructor and the
 * `LOCAL_CONSTRAINT` / `CLOUD_CONSTRAINT` system-prompt blocks consumed by
 * [SystemPromptManager] are preserved verbatim so the rest of the codebase
 * compiles unchanged.
 */
class AgentGovernor(
  /**
   * Maximum consecutive autonomous iterations before the circuit breaker halts
   * the loop to protect device stability. 25 strikes a balance: enough for
   * deep multi-step plans, short enough to prevent runaway battery drain.
   */
  val maxLoops: Int = 25,
) {
  // ─────────────────────────────────────────────────────────────────────────
  //  State machine
  // ─────────────────────────────────────────────────────────────────────────

  /** Phases of one autonomous iteration. */
  enum class Phase {
    IDLE,
    STREAMING_TOKENS,
    TOOL_CALL_DETECTED,
    EXECUTING_TOOL,
    RESUMING_STREAM,
    ERROR,
    EXHAUSTED,
  }

  /** A single tool execution as observed by the UI. */
  data class ToolExecution(
    val id: String,
    val name: String,
    val args: String,
    val phase: Phase,
    val resultPreview: String? = null,
    val errorMessage: String? = null,
    val startedAtMs: Long = System.currentTimeMillis(),
    val finishedAtMs: Long? = null,
  )

  private val _phase = MutableStateFlow(Phase.IDLE)
  val phase: StateFlow<Phase> = _phase.asStateFlow()

  private val _activeTool = MutableStateFlow<ToolExecution?>(null)

  /** Currently-executing tool (or `null` when idle / streaming pure tokens). */
  val activeTool: StateFlow<ToolExecution?> = _activeTool.asStateFlow()

  private val _engine = MutableStateFlow(AgentEngine.LOCAL)

  /** Currently-active engine. Settable by the chat screen when the user toggles. */
  val engine: StateFlow<AgentEngine> = _engine.asStateFlow()

  private val loopCount = AtomicInteger(0)

  // ─────────────────────────────────────────────────────────────────────────
  //  Hybrid routing
  // ─────────────────────────────────────────────────────────────────────────

  fun setEngine(next: AgentEngine) {
    _engine.value = next
    Log.d(TAG, "engine -> $next")
  }

  /** True iff the loop driver should call the Gemini Cloud endpoint. */
  val shouldRouteToCloud: Boolean get() = _engine.value == AgentEngine.CLOUD

  /** True iff the loop driver should call the on-device LiteRT model. */
  val shouldRouteToLocal: Boolean get() = _engine.value == AgentEngine.LOCAL

  // ─────────────────────────────────────────────────────────────────────────
  //  Loop transitions (called by AgentChatScreen)
  // ─────────────────────────────────────────────────────────────────────────

  /** Begin a new iteration; bumps the loop counter. */
  fun beginIteration() {
    val n = loopCount.incrementAndGet()
    if (n > maxLoops) {
      _phase.value = Phase.EXHAUSTED
      Log.w(TAG, "loop budget exhausted: $n > $maxLoops")
      return
    }
    _phase.value = Phase.STREAMING_TOKENS
  }

  /**
   * Signal that a tool call was detected in the streamed tokens. The streaming
   * is halted by the caller; this method merely transitions the state machine
   * and records the [ToolExecution] for the UI.
   */
  fun onToolCallDetected(name: String, argsJson: JSONObject): ToolExecution {
    val exec = ToolExecution(
      id = java.util.UUID.randomUUID().toString(),
      name = name,
      args = argsJson.toString().take(2_000),
      phase = Phase.EXECUTING_TOOL,
    )
    _activeTool.value = exec
    _phase.value = Phase.EXECUTING_TOOL
    Log.d(TAG, "tool call -> $name(${exec.args.take(120)})")
    return exec
  }

  /** Finalise the active tool with a successful result. */
  fun onToolResult(execId: String, resultPreview: String) {
    val current = _activeTool.value
    if (current != null && current.id == execId) {
      _activeTool.value = current.copy(
        phase = Phase.RESUMING_STREAM,
        resultPreview = resultPreview.take(2_000),
        finishedAtMs = System.currentTimeMillis(),
      )
    }
    _phase.value = Phase.RESUMING_STREAM
  }

  /** Finalise the active tool with a failure. */
  fun onToolError(execId: String, errorMessage: String) {
    val current = _activeTool.value
    if (current != null && current.id == execId) {
      _activeTool.value = current.copy(
        phase = Phase.ERROR,
        errorMessage = errorMessage.take(2_000),
        finishedAtMs = System.currentTimeMillis(),
      )
    }
    _phase.value = Phase.ERROR
  }

  /** Reset the governor between conversations / sessions. */
  fun reset() {
    loopCount.set(0)
    _activeTool.value = null
    _phase.value = Phase.IDLE
  }

  /** Snapshot useful for logging/diagnostics. */
  fun debugSnapshot(): String =
    "engine=${_engine.value} phase=${_phase.value} loops=${loopCount.get()}/$maxLoops tool=${_activeTool.value?.name}"

  // ─────────────────────────────────────────────────────────────────────────
  //  Backwards-compatible system-prompt constants
  // ─────────────────────────────────────────────────────────────────────────

  companion object {
    /** System constraint injected when running on a LOCAL engine (~4k context). */
    val LOCAL_CONSTRAINT: String = """
[LOCAL ENGINE CONSTRAINT]
Context window: ~4096 tokens. Be extremely concise in every response.
Prefer short tool calls over lengthy explanations. Omit preamble.
""".trimIndent()

    /** System constraint injected when running on the CLOUD engine. */
    val CLOUD_CONSTRAINT: String = """
[CLOUD ENGINE — Full Context Available]
Gemini API model active. Structured, detailed responses are acceptable.
Use best practices for code quality and explanations.
""".trimIndent()
  }
}

/** Convenience: subscribe to phase transitions as a hot [Flow] for UI use. */
fun AgentGovernor.phaseFlow(): Flow<AgentGovernor.Phase> = phase
