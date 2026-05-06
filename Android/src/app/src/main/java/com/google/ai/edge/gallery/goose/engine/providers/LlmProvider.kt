/*
 * Copyright 2026 Flynn013 / CLU/BOX
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package com.google.ai.edge.gallery.goose.engine.providers

import com.google.ai.edge.gallery.goose.engine.ConversationMessage
import kotlinx.coroutines.flow.Flow
import org.json.JSONObject

/**
 * Interface for LLM providers that support tool use (function calling).
 * Each provider translates between a common format and its own API format.
 *
 * Ported from MaxFlynn13/goose-android (engine/providers/LlmProvider.kt).
 */
interface LlmProvider {
    val providerId: String
    val modelId: String

    /**
     * Non-streaming: send messages with tools, get full response.
     */
    suspend fun chat(
        messages: List<ConversationMessage>,
        tools: List<JSONObject>
    ): LlmResponse

    /**
     * Streaming: send messages with tools, get token-by-token response.
     */
    fun streamChat(
        messages: List<ConversationMessage>,
        tools: List<JSONObject>
    ): Flow<StreamEvent>
}

/** Complete response from an LLM provider. */
data class LlmResponse(
    val text: String,
    val thinking: String = "",
    val toolCalls: List<LlmToolCall> = emptyList(),
    val finishReason: String = "stop"
)

/** A tool call requested by the LLM. */
data class LlmToolCall(
    val id: String,
    val name: String,
    val input: JSONObject
)

/** Events emitted during streaming responses. */
sealed class StreamEvent {
    data class Token(val text: String) : StreamEvent()
    data class Thinking(val text: String) : StreamEvent()
    data class ToolCallStart(val id: String, val name: String) : StreamEvent()
    data class ToolCallInput(val id: String, val partialInput: String) : StreamEvent()
    data class ToolCallEnd(val id: String, val name: String, val input: JSONObject) : StreamEvent()
    data class Done(val fullText: String, val toolCalls: List<LlmToolCall>) : StreamEvent()
    data class Error(val message: String) : StreamEvent()
}
