/*
 * Copyright 2026 Flynn013 / CLU/BOX
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

package com.google.ai.edge.gallery.data.providers

import android.util.Log
import com.google.ai.edge.gallery.customtasks.agentchat.ContinuousAgentDriver
import com.google.ai.edge.gallery.customtasks.agentchat.SkillRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * [LlmProvider] adapter for the CLU/BOX on-device LiteRT-LM inference engine.
 *
 * Bridges the standard [LlmProvider] interface onto the existing CLU/BOX
 * [ContinuousAgentDriver.InferenceAdapter] contract.  The protected LiteRT runtime
 * is **never** modified — this class only wraps it through the existing adapter.
 *
 * **Streaming**: tokens are emitted via a [Channel] connected to the `onToken` callback
 * of [ContinuousAgentDriver.InferenceAdapter.runTurn].
 *
 * Tool calls returned by the on-device model are surfaced as [ProviderEvent.ToolCallEnd]
 * followed by [ProviderEvent.Done].
 *
 * @param inferenceAdapter  The LiteRT or LiteRT-Cloud inference adapter already wired in
 *                          the active [ContinuousAgentDriver] instance.
 * @param skillRegistry     The [SkillRegistry] that knows which tool names are valid
 *                          so the provider can emit correct tool-call events.
 * @param modelName         Human-readable model name for logging and session records.
 */
class LiteRTProvider(
    private val inferenceAdapter: ContinuousAgentDriver.InferenceAdapter,
    private val skillRegistry: SkillRegistry,
    override val modelId: String,
    private val modelName: String = modelId,
) : LlmProvider {

    override val providerId: String = "litert"

    companion object {
        private const val TAG = "LiteRTProvider"
    }

    // ── Public API ──────────────────────────────────────────────────────────

    /**
     * Non-streaming inference — runs a single turn through the on-device model and
     * returns the complete [ProviderResponse].
     *
     * Note: tool calls returned by the LiteRT model are decoded from the standard
     * `TurnOutcome.ToolCall` shape and wrapped into [ProviderToolCallResult].
     */
    override suspend fun chat(
        messages: List<ProviderMessage>,
        tools: List<JSONObject>,
    ): ProviderResponse = withContext(Dispatchers.IO) {
        try {
            val contextEntries = messages.map { msg ->
                ContinuousAgentDriver.ContextEntry(
                    role = when (msg.role) {
                        "system" -> ContinuousAgentDriver.ContextEntry.Role.SYSTEM
                        "user" -> ContinuousAgentDriver.ContextEntry.Role.USER
                        "assistant" -> ContinuousAgentDriver.ContextEntry.Role.ASSISTANT
                        else -> ContinuousAgentDriver.ContextEntry.Role.TOOL
                    },
                    content = msg.content,
                )
            }

            val accText = StringBuilder()
            val outcome = inferenceAdapter.runTurn(contextEntries) { tok -> accText.append(tok) }

            when (outcome) {
                is ContinuousAgentDriver.TurnOutcome.Done ->
                    ProviderResponse(text = outcome.finalText, finishReason = "stop")

                is ContinuousAgentDriver.TurnOutcome.ToolCall -> {
                    val input = runCatching { JSONObject(outcome.argsJson) }.getOrElse { JSONObject() }
                    ProviderResponse(
                        text = accText.toString(),
                        toolCalls = listOf(ProviderToolCallResult(outcome.callId, outcome.tool, input)),
                        finishReason = "tool_calls",
                    )
                }

                is ContinuousAgentDriver.TurnOutcome.Failure ->
                    ProviderResponse(text = "", finishReason = "error")
            }
        } catch (e: Exception) {
            Log.e(TAG, "chat() error", e)
            ProviderResponse(text = "", finishReason = "error")
        }
    }

    /**
     * Streaming inference — runs a single turn and emits token events in real time.
     *
     * Unlike cloud providers, on-device models don't stream tool-call arguments
     * incrementally, so `ToolCallStart` / `ToolCallInput` events are synthesised
     * atomically when the turn completes with a tool call.
     */
    override fun streamChat(
        messages: List<ProviderMessage>,
        tools: List<JSONObject>,
    ): Flow<ProviderEvent> = flow {
        try {
            val contextEntries = messages.map { msg ->
                ContinuousAgentDriver.ContextEntry(
                    role = when (msg.role) {
                        "system" -> ContinuousAgentDriver.ContextEntry.Role.SYSTEM
                        "user" -> ContinuousAgentDriver.ContextEntry.Role.USER
                        "assistant" -> ContinuousAgentDriver.ContextEntry.Role.ASSISTANT
                        else -> ContinuousAgentDriver.ContextEntry.Role.TOOL
                    },
                    content = msg.content,
                )
            }

            val accText = StringBuilder()
            val outcome = withContext(Dispatchers.IO) {
                inferenceAdapter.runTurn(contextEntries) { tok ->
                    accText.append(tok)
                }
            }
            // Emit the accumulated text as a single Token (on-device models buffer internally)
            if (accText.isNotEmpty()) emit(ProviderEvent.Token(accText.toString()))

            when (outcome) {
                is ContinuousAgentDriver.TurnOutcome.Done -> {
                    emit(ProviderEvent.Done(outcome.finalText, emptyList()))
                }
                is ContinuousAgentDriver.TurnOutcome.ToolCall -> {
                    val input = runCatching { JSONObject(outcome.argsJson) }.getOrElse { JSONObject() }
                    emit(ProviderEvent.ToolCallStart(outcome.callId, outcome.tool))
                    emit(ProviderEvent.ToolCallInput(outcome.callId, outcome.argsJson))
                    val result = ProviderToolCallResult(outcome.callId, outcome.tool, input)
                    emit(ProviderEvent.ToolCallEnd(outcome.callId, outcome.tool, input))
                    emit(ProviderEvent.Done(accText.toString(), listOf(result)))
                }
                is ContinuousAgentDriver.TurnOutcome.Failure -> {
                    emit(ProviderEvent.Error(outcome.message))
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "streamChat() error", e)
            emit(ProviderEvent.Error(e.message ?: "On-device inference error"))
        }
    }
}
