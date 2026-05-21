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

// Streaming SSE parsing pattern adapted from Flynn013/SPL-NTR AnthropicProvider (Apache-2.0),
// ported to Ktor (already a CLU/BOX dependency) instead of OkHttp.

package com.google.ai.edge.gallery.data.providers

import android.util.Log
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.headers
import io.ktor.client.request.preparePost
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpStatusCode
import io.ktor.utils.io.readUTF8Line
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import org.json.JSONArray
import org.json.JSONObject

/**
 * [LlmProvider] implementation for the Anthropic Claude API.
 *
 * Sends messages via the `/v1/messages` endpoint using SSE streaming.
 * Supports extended thinking (chain-of-thought) via [ProviderEvent.Thinking] events.
 *
 * @param apiKey      Anthropic API key (used when [bearerToken] is null)
 * @param modelId     Model identifier, e.g. "claude-3-5-sonnet-20241022"
 * @param bearerToken OAuth 2.0 bearer token; when set, uses `Authorization: Bearer` header
 *                    instead of `x-api-key` and adds the `oauth-2025-04-20` beta header.
 */
class AnthropicProvider(
    private val apiKey: String,
    override val modelId: String,
    private val bearerToken: String? = null,
) : LlmProvider {

    override val providerId: String = "anthropic"

    private val httpClient = HttpClient(CIO) {
        install(HttpTimeout) {
            requestTimeoutMillis = 120_000L
            connectTimeoutMillis = 60_000L
            socketTimeoutMillis = 120_000L
        }
    }

    companion object {
        private const val TAG = "AnthropicProvider"
        private const val BASE_URL = "https://api.anthropic.com/v1/messages"
        private const val API_VERSION = "2023-06-01"
        private const val MAX_TOKENS = 8192
    }

    // ── Public API ──────────────────────────────────────────────────────────

    override suspend fun chat(
        messages: List<ProviderMessage>,
        tools: List<JSONObject>,
    ): ProviderResponse = withContext(Dispatchers.IO) {
        try {
            val bodyJson = buildRequestBody(messages, tools, stream = false)
            var responseBody = ""
            httpClient.preparePost(BASE_URL) {
                headers {
                    if (bearerToken != null) {
                        append("Authorization", "Bearer $bearerToken")
                        append("anthropic-beta", "oauth-2025-04-20")
                    } else {
                        append("x-api-key", apiKey)
                    }
                    append("anthropic-version", API_VERSION)
                    append("Content-Type", "application/json")
                }
                setBody(bodyJson)
            }.execute { resp ->
                responseBody = resp.bodyAsChannel().let { ch ->
                    buildString {
                        while (!ch.isClosedForRead) {
                            append(ch.readUTF8Line() ?: break)
                            append("\n")
                        }
                    }
                }
            }
            parseFullResponse(JSONObject(responseBody))
        } catch (e: Exception) {
            Log.e(TAG, "chat() error: ${e.message}", e)
            ProviderResponse(text = "", finishReason = "error")
        }
    }

    override fun streamChat(
        messages: List<ProviderMessage>,
        tools: List<JSONObject>,
    ): Flow<ProviderEvent> = flow {
        try {
            val bodyJson = buildRequestBody(messages, tools, stream = true)

                val fullText = StringBuilder()
                val thinkingText = StringBuilder()
                val toolCalls = mutableListOf<ProviderToolCallResult>()
                val toolInputBuffers = mutableMapOf<Int, StringBuilder>()
                val toolCallIds = mutableMapOf<Int, String>()
                val toolCallNames = mutableMapOf<Int, String>()
                var currentBlockIndex = -1

                httpClient.preparePost(BASE_URL) {
                    headers {
                        if (bearerToken != null) {
                            append("Authorization", "Bearer $bearerToken")
                            append("anthropic-beta", "oauth-2025-04-20")
                        } else {
                            append("x-api-key", apiKey)
                        }
                        append("anthropic-version", API_VERSION)
                        append("Content-Type", "application/json")
                    }
                    setBody(bodyJson)
                }.execute { response ->
                    if (response.status != HttpStatusCode.OK) {
                        val errBody = buildString {
                            val ch = response.bodyAsChannel()
                            while (!ch.isClosedForRead) append(ch.readUTF8Line() ?: break)
                        }
                        emit(ProviderEvent.Error("HTTP ${response.status.value}: $errBody"))
                        return@execute
                    }

                    val channel = response.bodyAsChannel()
                    while (!channel.isClosedForRead) {
                        val line = channel.readUTF8Line() ?: break
                        if (!line.startsWith("data: ")) continue
                        val data = line.removePrefix("data: ").trim()
                        if (data.isEmpty()) continue

                        try {
                            val event = JSONObject(data)
                            when (event.optString("type", "")) {
                                "content_block_start" -> {
                                    currentBlockIndex = event.optInt("index", -1)
                                    val block = event.optJSONObject("content_block")
                                    val blockType = block?.optString("type", "") ?: ""
                                    if (blockType == "tool_use") {
                                        val toolId = block?.optString("id", "") ?: ""
                                        val toolName = block?.optString("name", "") ?: ""
                                        toolCallIds[currentBlockIndex] = toolId
                                        toolCallNames[currentBlockIndex] = toolName
                                        toolInputBuffers[currentBlockIndex] = StringBuilder()
                                        emit(ProviderEvent.ToolCallStart(toolId, toolName))
                                    }
                                }

                                "content_block_delta" -> {
                                    val index = event.optInt("index", currentBlockIndex)
                                    val delta = event.optJSONObject("delta") ?: continue
                                    when (delta.optString("type", "")) {
                                        "text_delta" -> {
                                            val text = delta.optString("text", "")
                                            if (text.isNotEmpty()) {
                                                fullText.append(text)
                                                emit(ProviderEvent.Token(text))
                                            }
                                        }
                                        "thinking_delta" -> {
                                            val thinking = delta.optString("thinking", "")
                                            if (thinking.isNotEmpty()) {
                                                thinkingText.append(thinking)
                                                emit(ProviderEvent.Thinking(thinking))
                                            }
                                        }
                                        "input_json_delta" -> {
                                            val partial = delta.optString("partial_json", "")
                                            if (partial.isNotEmpty()) {
                                                toolInputBuffers[index]?.append(partial)
                                                val toolId = toolCallIds[index] ?: ""
                                                emit(ProviderEvent.ToolCallInput(toolId, partial))
                                            }
                                        }
                                    }
                                }

                                "content_block_stop" -> {
                                    val index = event.optInt("index", currentBlockIndex)
                                    val toolId = toolCallIds[index]
                                    val toolName = toolCallNames[index]
                                    val inputBuffer = toolInputBuffers[index]
                                    if (toolId != null && toolName != null && inputBuffer != null) {
                                        val inputJson = try {
                                            JSONObject(inputBuffer.toString())
                                        } catch (_: Exception) { JSONObject() }
                                        val result = ProviderToolCallResult(toolId, toolName, inputJson)
                                        toolCalls.add(result)
                                        emit(ProviderEvent.ToolCallEnd(toolId, toolName, inputJson))
                                    }
                                }

                                "message_stop" -> {
                                    emit(ProviderEvent.Done(fullText.toString(), toolCalls.toList()))
                                }

                                "error" -> {
                                    val errMsg = event.optJSONObject("error")
                                        ?.optString("message", "Unknown error") ?: "Unknown error"
                                    Log.e(TAG, "Stream error event: $errMsg")
                                    emit(ProviderEvent.Error(errMsg))
                                }
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "SSE parse error: ${e.message}")
                        }
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "streamChat() error: ${e.message}", e)
                emit(ProviderEvent.Error(e.message ?: "Unknown streaming error"))
            }
    }.flowOn(Dispatchers.IO)

    // ── Request building ────────────────────────────────────────────────────

    private fun buildRequestBody(
        messages: List<ProviderMessage>,
        tools: List<JSONObject>,
        stream: Boolean,
    ): String {
        val body = JSONObject()
        body.put("model", modelId)
        body.put("max_tokens", MAX_TOKENS)
        body.put("stream", stream)

        val systemMessages = messages.filter { it.role == "system" }
        val nonSystemMessages = messages.filter { it.role != "system" }

        if (systemMessages.isNotEmpty()) {
            body.put("system", systemMessages.joinToString("\n") { it.content })
        }

        val messagesArray = JSONArray()
        for (msg in groupMessagesForAnthropic(nonSystemMessages)) messagesArray.put(msg)
        body.put("messages", messagesArray)

        if (tools.isNotEmpty()) {
            val toolsArray = JSONArray()
            for (tool in tools) toolsArray.put(convertToolToAnthropicFormat(tool))
            body.put("tools", toolsArray)
        }

        return body.toString()
    }

    /**
     * Anthropic requires "user" messages to include tool_result blocks, and consecutive tool
     * results must be batched into a single user message.
     */
    private fun groupMessagesForAnthropic(messages: List<ProviderMessage>): List<JSONObject> {
        val result = mutableListOf<JSONObject>()
        var i = 0
        while (i < messages.size) {
            val msg = messages[i]
            when (msg.role) {
                "user" -> {
                    val msgObj = JSONObject().apply { put("role", "user") }
                    val content = JSONArray()
                    content.put(JSONObject().apply { put("type", "text"); put("text", msg.content) })
                    msgObj.put("content", content)
                    result.add(msgObj)
                }
                "assistant" -> {
                    val msgObj = JSONObject().apply { put("role", "assistant") }
                    val content = JSONArray()
                    if (msg.content.isNotEmpty()) {
                        content.put(JSONObject().apply { put("type", "text"); put("text", msg.content) })
                    }
                    for (tc in msg.toolCalls) {
                        content.put(JSONObject().apply {
                            put("type", "tool_use")
                            put("id", tc.id)
                            put("name", tc.name)
                            try { put("input", JSONObject(tc.inputJson)) } catch (e: Exception) {
                        Log.w(TAG, "Failed to parse tool input JSON for '${tc.name}': ${e.message}")
                        put("input", JSONObject())
                    }
                        })
                    }
                    msgObj.put("content", content)
                    result.add(msgObj)
                }
                "tool" -> {
                    val msgObj = JSONObject().apply { put("role", "user") }
                    val content = JSONArray()
                    fun addToolResult(t: ProviderMessage) {
                        content.put(JSONObject().apply {
                            put("type", "tool_result")
                            put("tool_use_id", t.toolCallId ?: "")
                            put("content", t.content)
                        })
                    }
                    addToolResult(msg)
                    // Batch consecutive tool results into the same user message
                    var j = i + 1
                    while (j < messages.size && messages[j].role == "tool") {
                        addToolResult(messages[j])
                        j++
                    }
                    msgObj.put("content", content)
                    result.add(msgObj)
                    i = j - 1
                }
            }
            i++
        }
        return result
    }

    private fun convertToolToAnthropicFormat(tool: JSONObject): JSONObject {
        val src = tool.optJSONObject("function") ?: tool
        return JSONObject().apply {
            put("name", src.optString("name", ""))
            put("description", src.optString("description", ""))
            val schema = src.optJSONObject("input_schema")
                ?: src.optJSONObject("parameters")
                ?: JSONObject().apply { put("type", "object"); put("properties", JSONObject()) }
            put("input_schema", schema)
        }
    }

    // ── Response parsing ────────────────────────────────────────────────────

    private fun parseFullResponse(json: JSONObject): ProviderResponse {
        val contentArray = json.optJSONArray("content") ?: JSONArray()
        val stopReason = json.optString("stop_reason", "stop")
        val textParts = StringBuilder()
        val thinkingParts = StringBuilder()
        val toolCalls = mutableListOf<ProviderToolCallResult>()

        for (i in 0 until contentArray.length()) {
            val block = contentArray.getJSONObject(i)
            when (block.optString("type", "")) {
                "text" -> textParts.append(block.optString("text", ""))
                "thinking" -> thinkingParts.append(block.optString("thinking", ""))
                "tool_use" -> toolCalls.add(
                    ProviderToolCallResult(
                        id = block.optString("id", ""),
                        name = block.optString("name", ""),
                        input = block.optJSONObject("input") ?: JSONObject(),
                    )
                )
            }
        }

        return ProviderResponse(
            text = textParts.toString(),
            thinking = thinkingParts.toString(),
            toolCalls = toolCalls,
            finishReason = stopReason,
        )
    }
}
