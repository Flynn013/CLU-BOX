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

// Pattern adapted from Flynn013/SPL-NTR (Apache-2.0) — provider interface shape and
// ProviderEvent sealed class adapted from engine/providers/LlmProvider.kt and data/models/.

package com.google.ai.edge.gallery.data.providers

import kotlinx.coroutines.flow.Flow
import org.json.JSONObject

/**
 * Common streaming-first LLM provider interface used by the CLU/BOX agentic engine.
 *
 * All cloud providers ([GeminiProvider], [AnthropicProvider], [OpenAIProvider]) and the
 * on-device [LiteRTProvider] implement this contract so the agent loop, tool router, and
 * session manager are provider-agnostic.
 *
 * Tool schemas are passed in OpenAI-function-calling wire format:
 * ```json
 * {
 *   "type": "function",
 *   "function": { "name": "…", "description": "…", "parameters": { … } }
 * }
 * ```
 * Each provider is responsible for converting that common schema to its own API format.
 */
interface LlmProvider {
    /** Short identifier used in logs, session records, and the provider registry (e.g. "gemini"). */
    val providerId: String

    /** The model identifier sent to the API (e.g. "gemini-2.0-flash"). */
    val modelId: String

    /**
     * Non-streaming completion — returns after the full response has been received.
     *
     * Use [streamChat] for live UI token rendering. This overload is useful for
     * background tasks and unit tests.
     */
    suspend fun chat(
        messages: List<ProviderMessage>,
        tools: List<JSONObject> = emptyList(),
    ): ProviderResponse

    /**
     * Streaming completion — emits a cold [Flow] of [ProviderEvent]s that the agent
     * loop collects to drive token rendering, tool execution, and turn management.
     *
     * The flow is cold: a new HTTP/inference request is started on each collection.
     * Cancel the collecting coroutine to abort mid-stream.
     */
    fun streamChat(
        messages: List<ProviderMessage>,
        tools: List<JSONObject> = emptyList(),
    ): Flow<ProviderEvent>
}

// ── Message types ─────────────────────────────────────────────────────────────

/**
 * A single turn in the conversation history passed to [LlmProvider.chat] /
 * [LlmProvider.streamChat].
 *
 * @param role        "system" | "user" | "assistant" | "tool"
 * @param content     Plain-text content (may be empty for assistant turns that only have tool calls)
 * @param toolCalls   Tool calls made by the assistant in this turn (non-empty for assistant turns
 *                    that triggered tools)
 * @param toolCallId  ID correlating a "tool" role message back to its request (non-null for role=tool)
 * @param toolName    Name of the tool whose result this message carries (non-null for role=tool)
 */
data class ProviderMessage(
    val role: String,
    val content: String = "",
    val toolCalls: List<ProviderToolCall> = emptyList(),
    val toolCallId: String? = null,
    val toolName: String? = null,
)

/**
 * A tool call requested by the assistant, as stored inside a [ProviderMessage] with role=assistant.
 */
data class ProviderToolCall(
    val id: String,
    val name: String,
    /** Raw JSON string of the tool's input arguments. */
    val inputJson: String,
)

// ── Response types ─────────────────────────────────────────────────────────────

/**
 * Complete response returned by [LlmProvider.chat].
 */
data class ProviderResponse(
    val text: String,
    val thinking: String = "",
    val toolCalls: List<ProviderToolCallResult> = emptyList(),
    val finishReason: String = "stop",
)

/**
 * A parsed tool call result returned inside [ProviderResponse.toolCalls].
 */
data class ProviderToolCallResult(
    val id: String,
    val name: String,
    val input: JSONObject,
)

// ── Streaming events ───────────────────────────────────────────────────────────

/**
 * Events emitted by [LlmProvider.streamChat].
 *
 * The agent loop consumes these to update the UI and orchestrate tool execution.
 */
sealed class ProviderEvent {
    /** A text token from the assistant response. */
    data class Token(val text: String) : ProviderEvent()

    /** A chain-of-thought reasoning token (extended thinking / thinking mode). */
    data class Thinking(val text: String) : ProviderEvent()

    /** The start of a new tool call — id and name are known, input is still streaming. */
    data class ToolCallStart(val id: String, val name: String) : ProviderEvent()

    /** Partial JSON input arriving for a tool call that is still being streamed. */
    data class ToolCallInput(val id: String, val partialInput: String) : ProviderEvent()

    /** A tool call is fully parsed and ready for execution. */
    data class ToolCallEnd(val id: String, val name: String, val input: JSONObject) : ProviderEvent()

    /** The stream is complete. [fullText] is the complete accumulated text; [toolCalls] the parsed
     *  calls (authoritative — use over incremental tracking in case of partial-event races). */
    data class Done(val fullText: String, val toolCalls: List<ProviderToolCallResult>) : ProviderEvent()

    /** An error occurred during the stream. */
    data class Error(val message: String) : ProviderEvent()
}
