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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "ContinuousAgentDriver"

/**
 * **ContinuousAgentDriver** — Goose-style autonomous execution loop.
 *
 * The driver wraps the existing [AgentLoopManager] (error-budget) and
 * [AgentGovernor] (state machine) and orchestrates them into the continuous
 * agentic cycle the spec describes:
 *
 * ```
 * ┌──────────────────────────────────────────────────────────────────┐
 * │ 1. STREAM_TOKENS    — pull tokens from the active inference engine│
 * │ 2. DETECT_TOOL_CALL — parse function-call envelope                 │
 * │ 3. HALT_STREAM      — pause token consumption                      │
 * │ 4. EXECUTE_NATIVE   — invoke the Kotlin/Python tool                │
 * │ 5. APPEND_RESULT    — push typed observation back into context     │
 * │ 6. RESUME_STREAM    — let the model react and emit the next token  │
 * │ 7. GOTO 1 until the model emits the explicit DONE marker, the user │
 * │    cancels, or the error budget is exhausted.                      │
 * └──────────────────────────────────────────────────────────────────┘
 * ```
 *
 * The driver is engine-agnostic: it talks to whichever inference adapter the
 * caller injects via [InferenceAdapter] (Gemini Cloud or local LiteRT). The
 * adapter is responsible for actually streaming tokens; the driver is
 * responsible for the *control flow* around that stream.
 *
 * The driver is **not** a singleton: AgentChatScreen creates one per chat
 * session so cancellation is cheap. Use [stop] to interrupt the loop early.
 */
class ContinuousAgentDriver(
  private val governor: AgentGovernor,
  private val loopManager: AgentLoopManager,
  private val agentTools: AgentTools,
  private val skillRegistry: SkillRegistry,
  private val inference: InferenceAdapter,
) {

  /** Inference adapter contract — implemented separately for Gemini and LiteRT. */
  interface InferenceAdapter {
    /**
     * Run a single inference turn against the model.
     *
     * @param contextWindow The accumulated context (prior turns + tool obs).
     * @param onToken       Called for every streamed token (UI updates).
     * @return A [TurnOutcome] describing the result of the turn.
     */
    suspend fun runTurn(
      contextWindow: List<ContextEntry>,
      onToken: (String) -> Unit,
    ): TurnOutcome
  }

  /** The classified outcome of a single inference turn. */
  sealed interface TurnOutcome {
    /** Model emitted the explicit completion marker — task done. */
    data class Done(val finalText: String) : TurnOutcome

    /** Model requested a tool — driver should pause and execute it. */
    data class ToolCall(val tool: String, val argsJson: String, val callId: String) : TurnOutcome

    /** Model needs more input or hit an inference error. */
    data class Failure(val message: String) : TurnOutcome
  }

  /** Typed context-window entry. */
  data class ContextEntry(val role: Role, val content: String) {
    enum class Role { USER, ASSISTANT, TOOL, SYSTEM }
  }

  // ── Public state ─────────────────────────────────────────────────────────

  /** Hot stream of streamed tokens for live UI rendering. */
  private val _tokens = MutableStateFlow("")
  val tokens: StateFlow<String> = _tokens.asStateFlow()

  /** Whether the loop is currently running. */
  private val _running = MutableStateFlow(false)
  val running: StateFlow<Boolean> = _running.asStateFlow()

  /** The accumulated context window. The driver mutates this in-place. */
  private val context: MutableList<ContextEntry> = mutableListOf()

  /** Per-driver coroutine scope; cancelled by [stop]. */
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

  // ── Lifecycle ────────────────────────────────────────────────────────────

  /**
   * Begin the continuous loop with [userPrompt] as the seed message.
   *
   * The loop runs until one of:
   * - The inference adapter returns [TurnOutcome.Done].
   * - The error budget in [loopManager] is exhausted.
   * - The caller invokes [stop].
   */
  fun start(userPrompt: String, systemPrompt: String? = null) {
    if (_running.value) {
      Log.w(TAG, "start() called while loop is already running — ignoring")
      return
    }
    if (systemPrompt != null) context.add(ContextEntry(ContextEntry.Role.SYSTEM, systemPrompt))
    context.add(ContextEntry(ContextEntry.Role.USER, userPrompt))

    scope.launch {
      _running.value = true
      try {
        runLoop()
      } catch (t: Throwable) {
        Log.e(TAG, "loop crashed", t)
      } finally {
        _running.value = false
      }
    }
  }

  /** Cancel the current loop. */
  fun stop() {
    Log.d(TAG, "stop() requested")
    scope.coroutineContext[kotlinx.coroutines.Job]?.cancelChildren()
  }

  /** Drop accumulated context (e.g. when the user resets the chat). */
  fun reset() {
    stop()
    context.clear()
    loopManager.reset()
    _tokens.value = ""
  }

  /** Read-only view of the current context window for inspection / persistence. */
  fun snapshotContext(): List<ContextEntry> = context.toList()

  // ── Internal loop ────────────────────────────────────────────────────────

  private suspend fun runLoop() {
    var safetyHops = 0
    val maxHops = 64 // hard upper bound to prevent runaway costs.

    while (scope.isActive && safetyHops < maxHops) {
      safetyHops++
      governor.beginIteration()
      loopManager.emitThinking(agentTools)

      val turn = withContext(Dispatchers.IO) {
        inference.runTurn(context) { tok ->
          _tokens.value = tok
        }
      }

      when (turn) {
        is TurnOutcome.Done -> {
          context.add(ContextEntry(ContextEntry.Role.ASSISTANT, turn.finalText))
          loopManager.onSuccess()
          Log.d(TAG, "loop complete after $safetyHops hop(s)")
          return
        }

        is TurnOutcome.ToolCall -> {
          val parsedArgs = runCatching { org.json.JSONObject(turn.argsJson) }.getOrElse { org.json.JSONObject() }
          val exec = governor.onToolCallDetected(turn.tool, parsedArgs)
          loopManager.emitStatus(AgentLoopManager.statusExecuting(turn.tool), agentTools, true)

          val (ok, observation) = runCatching {
            executeTool(turn.tool, turn.argsJson)
          }.fold(
            onSuccess = { it },
            onFailure = { false to (it.message ?: "tool crashed") },
          )

          loopManager.emitStatus(AgentLoopManager.statusObserving(turn.tool), agentTools, false)

          if (ok) {
            governor.onToolResult(exec.id, observation)
            context.add(ContextEntry(ContextEntry.Role.TOOL, "${turn.tool} -> $observation"))
            loopManager.onSuccess()
          } else {
            governor.onToolError(exec.id, observation)
            val keepGoing = loopManager.onError(observation)
            val sysMsg = loopManager.formatRetrySystemMessage(turn.tool, observation)
            context.add(ContextEntry(ContextEntry.Role.SYSTEM, sysMsg))
            if (!keepGoing) {
              context.add(
                ContextEntry(
                  ContextEntry.Role.SYSTEM,
                  loopManager.buildExhaustedMessage(governor.engine.value),
                ),
              )
              return
            }
          }
        }

        is TurnOutcome.Failure -> {
          val keepGoing = loopManager.onError(turn.message)
          if (!keepGoing) {
            context.add(
              ContextEntry(
                ContextEntry.Role.SYSTEM,
                loopManager.buildExhaustedMessage(governor.engine.value),
              ),
            )
            return
          }
          context.add(
            ContextEntry(
              ContextEntry.Role.SYSTEM,
              loopManager.formatRetrySystemMessage("inference", turn.message),
            ),
          )
        }
      }
    }

    if (safetyHops >= maxHops) {
      Log.w(TAG, "max-hop safety bound reached ($maxHops) — halting loop")
      context.add(
        ContextEntry(
          ContextEntry.Role.SYSTEM,
          "[CLU/BOX] Loop halted at safety bound of $maxHops hops to prevent runaway execution.",
        ),
      )
    }
  }

  /**
   * Dispatch one tool call through [SkillRegistry.dispatch].
   *
   * Returns `(success, observation)` so the loop can append the typed
   * observation to the context regardless of outcome.
   */
  private suspend fun executeTool(tool: String, argsJson: String): Pair<Boolean, String> {
    Log.d(TAG, "executeTool name=$tool args=${argsJson.take(120)}")
    return runCatching {
      val args = if (argsJson.isBlank()) org.json.JSONObject() else org.json.JSONObject(argsJson)
      val result = skillRegistry.dispatch(tool, args)
      // Convention: SkillRegistry.dispatch returns a `[System Error: …]` or
      // `[System: …]` envelope when something refused to run; treat those as
      // failures so the error budget kicks in.
      val ok = !(result.startsWith("[System Error") || result.startsWith("[System: Skill"))
      ok to result
    }.getOrElse { false to (it.message ?: "tool dispatch failed") }
  }
}
