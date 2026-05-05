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

// Streaming SSE parsing pattern adapted from Flynn013/SPL-NTR GoogleProvider (Apache-2.0),
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
import java.util.UUID

/**
 * [LlmProvider] implementation for the Google Gemini API.
 *
 * Sends messages via the `generateContent` / `streamGenerateContent` endpoints of the
 * Generative Language REST API.  Tool schemas arrive in OpenAI function-calling format and
 * are converted to Google's `functionDeclarations` format on the fly.
 *
 * @param apiKey  Gemini API key (obtained from aistudio.google.com or GeminiApiKeyStore)
 * @param modelId Model identifier, e.g. "gemini-2.0-flash" or "gemini-1.5-pro"
 */
class GeminiProvider(
    private val apiKey: String,
    override val modelId: String,
) : LlmProvider {

    override val providerId: String = "gemini"

    private val httpClient = HttpClient(CIO) {
        install(HttpTimeout) {
            requestTimeoutMillis = 120_000L
            connectTimeoutMillis = 60_000L
            socketTimeoutMillis = 120_000L
        }
    }

    companion object {
        private const val TAG = "GeminiProvider"
        private const val BASE_URL =
            "https://generativelanguage.googleapis.com/v1beta/models"
    }

    // ── Public API ──────────────────────────────────────────────────────────

    override suspend fun chat(
        messages: List<ProviderMessage>,
        tools: List<JSONObject>,
    ): ProviderResponse = withContext(Dispatchers.IO) {
        try {
            val bodyJson = buildRequestBody(messages, tools)
            val url = "$BASE_URL/$modelId:generateContent"
            var responseBody = ""
            httpClient.preparePost(url) {
                headers {
                    append("x-goog-api-key", apiKey)
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
                val bodyJson = buildRequestBody(messages, tools)
                val url = "$BASE_URL/$modelId:streamGenerateContent?alt=sse"

                val fullText = StringBuilder()
                val toolCalls = mutableListOf<ProviderToolCallResult>()
                var doneSent = false

                httpClient.preparePost(url) {
                    headers {
                        append("x-goog-api-key", apiKey)
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
                            val chunk = JSONObject(data)
                            val candidates = chunk.optJSONArray("candidates") ?: continue
                            if (candidates.length() == 0) continue

                            val candidate = candidates.getJSONObject(0)
                            val content = candidate.optJSONObject("content") ?: continue
                            val parts = content.optJSONArray("parts") ?: continue

                            for (i in 0 until parts.length()) {
                                val part = parts.getJSONObject(i)

                                val text = part.optString("text", "")
                                if (text.isNotEmpty()) {
                                    fullText.append(text)
                                    emit(ProviderEvent.Token(text))
                                }

                                val functionCall = part.optJSONObject("functionCall")
                                if (functionCall != null) {
                                    val name = functionCall.optString("name", "")
                                    val args = functionCall.optJSONObject("args") ?: JSONObject()
                                    val id = "call_${UUID.randomUUID().toString().replace("-", "").take(24)}"
                                    emit(ProviderEvent.ToolCallStart(id, name))
                                    emit(ProviderEvent.ToolCallInput(id, args.toString()))
                                    val result = ProviderToolCallResult(id, name, args)
                                    toolCalls.add(result)
                                    emit(ProviderEvent.ToolCallEnd(id, name, args))
                                }
                            }

                            val finishReason = candidate.optString("finishReason", "")
                            if (finishReason == "STOP" || finishReason == "MAX_TOKENS") {
                                emit(ProviderEvent.Done(fullText.toString(), toolCalls.toList()))
                                doneSent = true
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "SSE parse error: ${e.message}")
                        }
                    }

                    if (!doneSent) {
                        emit(ProviderEvent.Done(fullText.toString(), toolCalls.toList()))
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

    private fun buildRequestBody(messages: List<ProviderMessage>, tools: List<JSONObject>): String {
        val body = JSONObject()

        val systemMessages = messages.filter { it.role == "system" }
        val nonSystemMessages = messages.filter { it.role != "system" }

        if (systemMessages.isNotEmpty()) {
            val systemInstruction = JSONObject()
            val parts = JSONArray()
            val textPart = JSONObject().apply {
                put("text", systemMessages.joinToString("\n") { it.content })
            }
            parts.put(textPart)
            systemInstruction.put("parts", parts)
            body.put("systemInstruction", systemInstruction)
        }

        val contents = JSONArray()
        for (msg in nonSystemMessages) {
            buildContentFromMessage(msg)?.let { contents.put(it) }
        }
        body.put("contents", contents)

        if (tools.isNotEmpty()) {
            val toolsArray = JSONArray()
            val toolDeclarations = JSONObject()
            val functionDeclarations = JSONArray()
            for (tool in tools) functionDeclarations.put(convertToolToGoogleFormat(tool))
            toolDeclarations.put("functionDeclarations", functionDeclarations)
            toolsArray.put(toolDeclarations)
            body.put("tools", toolsArray)
        }

        body.put("generationConfig", JSONObject().apply { put("maxOutputTokens", 8192) })
        return body.toString()
    }

    private fun buildContentFromMessage(msg: ProviderMessage): JSONObject? {
        val contentObj = JSONObject()
        val parts = JSONArray()

        when (msg.role) {
            "user" -> {
                contentObj.put("role", "user")
                parts.put(JSONObject().apply { put("text", msg.content) })
            }
            "assistant" -> {
                contentObj.put("role", "model")
                if (msg.content.isNotEmpty()) {
                    parts.put(JSONObject().apply { put("text", msg.content) })
                }
                for (tc in msg.toolCalls) {
                    val functionCallPart = JSONObject()
                    val functionCall = JSONObject().apply {
                        put("name", tc.name)
                        try {
                            put("args", JSONObject(tc.inputJson))
                        } catch (_: Exception) {
                            put("args", JSONObject())
                        }
                    }
                    functionCallPart.put("functionCall", functionCall)
                    parts.put(functionCallPart)
                }
            }
            "tool" -> {
                contentObj.put("role", "user")
                val responseContent = JSONObject()
                try {
                    responseContent.put("result", JSONObject(msg.content))
                } catch (_: Exception) {
                    responseContent.put("result", msg.content)
                }
                val functionResponse = JSONObject().apply {
                    put("name", msg.toolName ?: "unknown")
                    put("response", responseContent)
                }
                parts.put(JSONObject().apply { put("functionResponse", functionResponse) })
            }
            else -> return null
        }

        if (parts.length() == 0) return null
        contentObj.put("parts", parts)
        return contentObj
    }

    private fun convertToolToGoogleFormat(tool: JSONObject): JSONObject {
        val functionObj = tool.optJSONObject("function") ?: tool
        val declaration = JSONObject().apply {
            put("name", functionObj.optString("name", ""))
            put("description", functionObj.optString("description", ""))
        }
        val schema = functionObj.optJSONObject("parameters")
            ?: functionObj.optJSONObject("input_schema")
            ?: JSONObject().apply { put("type", "OBJECT"); put("properties", JSONObject()) }
        declaration.put("parameters", convertSchemaTypes(schema))
        return declaration
    }

    /** Recursively uppercases JSON-schema type strings for the Google API. */
    private fun convertSchemaTypes(schema: JSONObject): JSONObject {
        val result = JSONObject()
        result.put("type", schema.optString("type", "object").uppercase())
        schema.optJSONObject("properties")?.let { props ->
            val converted = JSONObject()
            props.keys().forEach { key ->
                props.optJSONObject(key)?.let { converted.put(key, convertSchemaTypes(it)) }
            }
            result.put("properties", converted)
        }
        schema.optJSONArray("required")?.let { result.put("required", it) }
        schema.optString("description", "").takeIf { it.isNotEmpty() }?.let { result.put("description", it) }
        schema.optJSONArray("enum")?.let { result.put("enum", it) }
        schema.optJSONObject("items")?.let { result.put("items", convertSchemaTypes(it)) }
        return result
    }

    // ── Response parsing ────────────────────────────────────────────────────

    private fun parseFullResponse(json: JSONObject): ProviderResponse {
        val error = json.optJSONObject("error")
        if (error != null) {
            Log.e(TAG, "API error: ${error.optString("message")}")
            return ProviderResponse(text = "", finishReason = "error")
        }
        val candidates = json.optJSONArray("candidates")
            ?: return ProviderResponse(text = "", finishReason = "error")
        if (candidates.length() == 0) return ProviderResponse(text = "", finishReason = "error")

        val candidate = candidates.getJSONObject(0)
        val content = candidate.optJSONObject("content")
        val finishReason = candidate.optString("finishReason", "STOP")
        val parts = content?.optJSONArray("parts") ?: JSONArray()

        val textParts = StringBuilder()
        val toolCalls = mutableListOf<ProviderToolCallResult>()
        for (i in 0 until parts.length()) {
            val part = parts.getJSONObject(i)
            val text = part.optString("text", "")
            if (text.isNotEmpty()) textParts.append(text)
            part.optJSONObject("functionCall")?.let { fc ->
                val name = fc.optString("name", "")
                val args = fc.optJSONObject("args") ?: JSONObject()
                val id = "call_${UUID.randomUUID().toString().replace("-", "").take(24)}"
                toolCalls.add(ProviderToolCallResult(id, name, args))
            }
        }

        return ProviderResponse(
            text = textParts.toString(),
            toolCalls = toolCalls,
            finishReason = when (finishReason) {
                "STOP" -> "stop"
                "MAX_TOKENS" -> "length"
                "SAFETY" -> "safety"
                else -> finishReason.lowercase()
            },
        )
    }
}
