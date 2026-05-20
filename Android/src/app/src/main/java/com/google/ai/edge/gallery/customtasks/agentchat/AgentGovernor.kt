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
 * Drives the agent cycle:
 * Stream Tokens → Detect Tool Call → Halt Stream → Route to Skill Registry →
 * Execute → Append Result → Resume
 *
 * The governor stores the active [AgentEngine] and exposes [shouldRouteToCloud] /
 * [shouldRouteToLocal] so the loop driver can dispatch to the correct backend.
 *
 * Use [LOCAL_CONSTRAINT] (Gemma local) or [CLOUD_CONSTRAINT] (Gemini/Claude)
 * when building the system prompt — they are tuned for each backend's capabilities.
 */
class AgentGovernor(
    val maxLoops: Int = 100,
) {
    // ─────────────────────────────────────────────────────────────────────────
    //  State machine
    // ─────────────────────────────────────────────────────────────────────────

    enum class Phase {
        IDLE, STREAMING_TOKENS, TOOL_CALL_DETECTED, EXECUTING_TOOL, RESUMING_STREAM, ERROR, EXHAUSTED,
    }

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

    private val _phase       = MutableStateFlow(Phase.IDLE)
    val phase: StateFlow<Phase> = _phase.asStateFlow()

    private val _activeTool  = MutableStateFlow<ToolExecution?>(null)
    val activeTool: StateFlow<ToolExecution?> = _activeTool.asStateFlow()

    private val _toolHistory = MutableStateFlow<List<ToolExecution>>(emptyList())
    val toolHistory: StateFlow<List<ToolExecution>> = _toolHistory.asStateFlow()

    private val _engine      = MutableStateFlow(AgentEngine.LOCAL)
    val engine: StateFlow<AgentEngine> = _engine.asStateFlow()

    private val loopCount    = AtomicInteger(0)

    // ─────────────────────────────────────────────────────────────────────────
    //  Engine routing
    // ─────────────────────────────────────────────────────────────────────────

    fun setEngine(next: AgentEngine) { _engine.value = next; Log.d(TAG, "engine -> $next") }

    val shouldRouteToCloud: Boolean get() = _engine.value == AgentEngine.CLOUD
    val shouldRouteToLocal: Boolean get() = _engine.value == AgentEngine.LOCAL

    // ─────────────────────────────────────────────────────────────────────────
    //  Loop transitions
    // ─────────────────────────────────────────────────────────────────────────

    fun beginIteration() {
        val n = loopCount.incrementAndGet()
        if (n > maxLoops) { _phase.value = Phase.EXHAUSTED; Log.w(TAG, "exhausted: $n > $maxLoops"); return }
        _phase.value = Phase.STREAMING_TOKENS
    }

    fun onToolCallDetected(name: String, argsJson: JSONObject): ToolExecution {
        val exec = ToolExecution(id = java.util.UUID.randomUUID().toString(), name = name,
            args = argsJson.toString().take(2_000), phase = Phase.EXECUTING_TOOL)
        _activeTool.value = exec
        _phase.value = Phase.EXECUTING_TOOL
        Log.d(TAG, "tool call -> $name(${exec.args.take(120)})")
        return exec
    }

    fun onToolResult(execId: String, resultPreview: String) {
        _activeTool.value?.takeIf { it.id == execId }?.let { current ->
            val finished = current.copy(phase = Phase.RESUMING_STREAM,
                resultPreview = resultPreview.take(2_000), finishedAtMs = System.currentTimeMillis())
            _activeTool.value = finished
            _toolHistory.value = _toolHistory.value + finished
        }
        _phase.value = Phase.RESUMING_STREAM
    }

    fun onToolError(execId: String, errorMessage: String) {
        _activeTool.value?.takeIf { it.id == execId }?.let { current ->
            val finished = current.copy(phase = Phase.ERROR,
                errorMessage = errorMessage.take(2_000), finishedAtMs = System.currentTimeMillis())
            _activeTool.value = finished
            _toolHistory.value = _toolHistory.value + finished
        }
        _phase.value = Phase.ERROR
    }

    fun reset() {
        loopCount.set(0); _activeTool.value = null; _toolHistory.value = emptyList(); _phase.value = Phase.IDLE
    }

    fun markIdle() { _activeTool.value = null; _phase.value = Phase.IDLE }

    fun debugSnapshot(): String =
        "engine=${_engine.value} phase=${_phase.value} loops=${loopCount.get()}/$maxLoops tool=${_activeTool.value?.name}"

    // ─────────────────────────────────────────────────────────────────────────
    //  System-prompt constants — engine-specific
    // ─────────────────────────────────────────────────────────────────────────

    companion object {

        /**
         * Injected for the LOCAL on-device Gemma 4 E4B IT engine.
         *
         * Tightly budgeted for an 8K context: one tool per turn, BusyBox sh only,
         * and a strict CONTINUE rule to prevent the model from stalling after every step.
         */
        val LOCAL_CONSTRAINT: String = """
[GEMMA 4 E4B IT — 32K context. Be concise.]
1. One tool call per response → observe result → decide next step.
2. Task complete? YES → reply to user. NO → call next tool immediately.
3. Never ask permission to continue unless genuinely blocked.
4. memorySearch before answering questions about user context or past work.
5. fileBoxWrite for all file writes — never shell redirection.
6. BusyBox sh only — no Bash arrays, no process substitution.
""".trimIndent()

        /**
         * Injected for CLOUD providers (Gemini / Anthropic Claude).
         *
         * Cloud models have 128K+ context and support extended thinking.
         * The constraint is deliberately less restrictive than [LOCAL_CONSTRAINT]:
         * multiple tool calls may be planned before execution, and the model
         * is encouraged to use the richer tool set (fileEdit, codeSearch, fileDiff)
         * for code-quality workflows.
         */
        val CLOUD_CONSTRAINT: String = """
[Cloud Agent — 128K context. Extended reasoning enabled.]
1. Plan multi-step tasks explicitly, then execute each tool in sequence.
2. After each tool result, continue immediately if the task is not complete.
3. CODE WORKFLOW: codeSearch → fileBoxReadLines → fileEdit (preferred over full rewrites) → fileDiff to verify.
4. Store important findings to BrainBox before ending long sessions.
5. Use fileBoxWrite to create new files; use fileEdit for surgical updates to existing files.
6. memorySearch before answering questions about the user's projects or past decisions.
""".trimIndent()
    }
}

/** Convenience: subscribe to phase transitions as a hot [Flow] for UI use. */
fun AgentGovernor.phaseFlow(): Flow<AgentGovernor.Phase> = phase
