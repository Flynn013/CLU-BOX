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

// Streaming SSE parsing pattern adapted from Flynn013/SPL-NTR OpenAIProvider (Apache-2.0),
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
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * [LlmProvider] implementation for the OpenAI Chat Completions API (and any compatible endpoint).
 *
 * Compatible with openai.com, Groq, Together AI, local LM Studio / Ollama with OpenAI-compatible
 * endpoints, and any other service that speaks the OpenAI chat-completions wire format.
 *
 * @param apiKey  API key (use "lm-studio" or similar placeholder for local servers)
 * @param modelId Model identifier, e.g. "gpt-4o" or "llama3-8b"
 * @param baseUrl Base URL of the API (default: openai.com)
 */
class OpenAIProvider(
    private val apiKey: String,
    override val modelId: String,
    private val baseUrl: String = "https://api.openai.com/v1",
) : LlmProvider {

    override val providerId: String = "openai"

    private val httpClient = HttpClient(CIO) {
        install(HttpTimeout) {
            requestTimeoutMillis = 120_000L
            connectTimeoutMillis = 60_000L
            socketTimeoutMillis = 120_000L
        }
    }

    companion object {
        private const val TAG = "OpenAIProvider"
    }

    // ── Public API ──────────────────────────────────────────────────────────

    override suspend fun chat(
        messages: List<ProviderMessage>,
        tools: List<JSONObject>,
    ): ProviderResponse = withContext(Dispatchers.IO) {
        try {
            val bodyJson = buildRequestBody(messages, tools, stream = false)
            var responseBody = ""
            httpClient.preparePost("$baseUrl/chat/completions") {
                headers {
                    append("Authorization", "Bearer $apiKey")
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
        withContext(Dispatchers.IO) {
            try {
                val bodyJson = buildRequestBody(messages, tools, stream = true)

                val fullText = StringBuilder()
                val toolCalls = mutableListOf<ProviderToolCallResult>()
                val toolCallIds = mutableMapOf<Int, String>()
                val toolCallNames = mutableMapOf<Int, String>()
                val toolInputBuffers = mutableMapOf<Int, StringBuilder>()

                httpClient.preparePost("$baseUrl/chat/completions") {
                    headers {
                        append("Authorization", "Bearer $apiKey")
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
                        if (data == "[DONE]") {
                            // Finalize all pending (partially-streamed) tool calls
                            for ((index, inputBuffer) in toolInputBuffers) {
                                val id = toolCallIds[index] ?: continue
                                val name = toolCallNames[index] ?: continue
                                val inputJson = try { JSONObject(inputBuffer.toString()) } catch (_: Exception) { JSONObject() }
                                val result = ProviderToolCallResult(id, name, inputJson)
                                toolCalls.add(result)
                                emit(ProviderEvent.ToolCallEnd(id, name, inputJson))
                            }
                            emit(ProviderEvent.Done(fullText.toString(), toolCalls.toList()))
                            continue
                        }

                        try {
                            val chunk = JSONObject(data)
                            val choices = chunk.optJSONArray("choices") ?: continue
                            if (choices.length() == 0) continue

                            val choice = choices.getJSONObject(0)
                            val delta = choice.optJSONObject("delta") ?: continue

                            // Text content
                            val content = delta.optString("content", "")
                            if (content.isNotEmpty()) {
                                fullText.append(content)
                                emit(ProviderEvent.Token(content))
                            }

                            // Tool calls (streamed incrementally by index)
                            val deltaToolCalls = delta.optJSONArray("tool_calls")
                            if (deltaToolCalls != null) {
                                for (i in 0 until deltaToolCalls.length()) {
                                    val tc = deltaToolCalls.getJSONObject(i)
                                    val index = tc.optInt("index", 0)
                                    val id = tc.optString("id", "")
                                    val function = tc.optJSONObject("function")

                                    if (id.isNotEmpty()) {
                                        toolCallIds[index] = id
                                        val name = function?.optString("name", "") ?: ""
                                        toolCallNames[index] = name
                                        toolInputBuffers[index] = StringBuilder()
                                        emit(ProviderEvent.ToolCallStart(id, name))
                                    }

                                    val args = function?.optString("arguments", "") ?: ""
                                    if (args.isNotEmpty()) {
                                        toolInputBuffers[index]?.append(args)
                                        val toolId = toolCallIds[index] ?: ""
                                        emit(ProviderEvent.ToolCallInput(toolId, args))
                                    }
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
        }
    }

    // ── Request building ────────────────────────────────────────────────────

    private fun buildRequestBody(
        messages: List<ProviderMessage>,
        tools: List<JSONObject>,
        stream: Boolean,
    ): String {
        val body = JSONObject()
        body.put("model", modelId)
        body.put("stream", stream)

        val messagesArray = JSONArray()
        for (msg in messages) {
            val msgObj = JSONObject()
            when (msg.role) {
                "system" -> {
                    msgObj.put("role", "system")
                    msgObj.put("content", msg.content)
                }
                "user" -> {
                    msgObj.put("role", "user")
                    msgObj.put("content", msg.content)
                }
                "assistant" -> {
                    msgObj.put("role", "assistant")
                    if (msg.content.isNotEmpty()) msgObj.put("content", msg.content)
                    if (msg.toolCalls.isNotEmpty()) {
                        val tcArray = JSONArray()
                        for (tc in msg.toolCalls) {
                            tcArray.put(JSONObject().apply {
                                put("id", tc.id)
                                put("type", "function")
                                put("function", JSONObject().apply {
                                    put("name", tc.name)
                                    put("arguments", tc.inputJson)
                                })
                            })
                        }
                        msgObj.put("tool_calls", tcArray)
                    }
                }
                "tool" -> {
                    msgObj.put("role", "tool")
                    msgObj.put("content", msg.content)
                    msgObj.put("tool_call_id", msg.toolCallId ?: "")
                }
                else -> {
                    msgObj.put("role", msg.role)
                    msgObj.put("content", msg.content)
                }
            }
            messagesArray.put(msgObj)
        }
        body.put("messages", messagesArray)

        if (tools.isNotEmpty()) {
            val toolsArray = JSONArray()
            for (tool in tools) toolsArray.put(convertToolToOpenAIFormat(tool))
            body.put("tools", toolsArray)
        }

        return body.toString()
    }

    private fun convertToolToOpenAIFormat(tool: JSONObject): JSONObject {
        // If the tool is already in OpenAI format (has a "function" wrapper), pass it through.
        // If it uses Anthropic-style flat layout, wrap it.
        if (tool.has("type") && tool.has("function")) return tool

        val name = tool.optString("name", "")
        val description = tool.optString("description", "")
        val schema = tool.optJSONObject("parameters")
            ?: tool.optJSONObject("input_schema")
            ?: JSONObject().apply { put("type", "object"); put("properties", JSONObject()) }

        return JSONObject().apply {
            put("type", "function")
            put("function", JSONObject().apply {
                put("name", name)
                put("description", description)
                put("parameters", schema)
            })
        }
    }

    // ── Response parsing ────────────────────────────────────────────────────

    private fun parseFullResponse(json: JSONObject): ProviderResponse {
        val choices = json.optJSONArray("choices")
            ?: return ProviderResponse(text = "", finishReason = "error")
        if (choices.length() == 0) return ProviderResponse(text = "", finishReason = "error")

        val choice = choices.getJSONObject(0)
        val message = choice.optJSONObject("message") ?: JSONObject()
        val finishReason = choice.optString("finish_reason", "stop")
        val text = message.optString("content", "") ?: ""

        val toolCalls = mutableListOf<ProviderToolCallResult>()
        message.optJSONArray("tool_calls")?.let { tcArray ->
            for (i in 0 until tcArray.length()) {
                val tc = tcArray.getJSONObject(i)
                val id = tc.optString("id", "")
                val function = tc.optJSONObject("function")
                val name = function?.optString("name", "") ?: ""
                val argsStr = function?.optString("arguments", "{}")
                val input = try { JSONObject(argsStr) } catch (_: Exception) { JSONObject() }
                toolCalls.add(ProviderToolCallResult(id, name, input))
            }
        }

        return ProviderResponse(text = text, toolCalls = toolCalls, finishReason = finishReason)
    }
}
