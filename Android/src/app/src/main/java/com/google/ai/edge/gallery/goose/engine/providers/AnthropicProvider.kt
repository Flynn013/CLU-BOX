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

import android.util.Log
import com.google.ai.edge.gallery.goose.engine.ConversationMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

/**
 * Anthropic Claude provider with full tool use and SSE streaming support.
 *
 * Ported from MaxFlynn13/goose-android (engine/providers/AnthropicProvider.kt).
 */
class AnthropicProvider(
    private val apiKey: String,
    override val modelId: String
) : LlmProvider {

    override val providerId: String = "anthropic"

    private val client = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    companion object {
        private const val TAG = "AnthropicProvider"
        private const val BASE_URL = "https://api.anthropic.com/v1/messages"
        private const val API_VERSION = "2023-06-01"
        private const val MAX_TOKENS = 8192
        private val JSON_MEDIA_TYPE = "application/json".toMediaType()
    }

    override suspend fun chat(
        messages: List<ConversationMessage>,
        tools: List<JSONObject>
    ): LlmResponse = withContext(Dispatchers.IO) {
        try {
            val body = buildRequestBody(messages, tools, stream = false)
            val request = buildRequest(body)
            val response = client.newCall(request).execute()
            val responseBody = response.body?.string() ?: ""
            if (!response.isSuccessful) {
                Log.e(TAG, "HTTP ${response.code}: $responseBody")
                return@withContext LlmResponse(text = "", finishReason = "error")
            }
            parseFullResponse(JSONObject(responseBody))
        } catch (e: Exception) {
            Log.e(TAG, "Chat error: ${e.message}", e)
            LlmResponse(text = "", finishReason = "error")
        }
    }

    override fun streamChat(
        messages: List<ConversationMessage>,
        tools: List<JSONObject>
    ): Flow<StreamEvent> = callbackFlow {
        try {
            val body = buildRequestBody(messages, tools, stream = true)
            val request = buildRequest(body)
            val response = client.newCall(request).execute()

            if (!response.isSuccessful) {
                val errorBody = response.body?.string() ?: "Unknown error"
                Log.e(TAG, "Stream HTTP ${response.code}: $errorBody")
                trySend(StreamEvent.Error("HTTP ${response.code}: $errorBody"))
                close()
                return@callbackFlow
            }

            val reader = BufferedReader(InputStreamReader(response.body!!.byteStream()))
            val fullText = StringBuilder()
            val thinkingText = StringBuilder()
            val toolCalls = mutableListOf<LlmToolCall>()
            val toolInputBuffers = mutableMapOf<Int, StringBuilder>()
            val toolCallIds = mutableMapOf<Int, String>()
            val toolCallNames = mutableMapOf<Int, String>()
            var currentBlockIndex = -1
            var currentBlockType = ""

            var line: String?
            while (reader.readLine().also { line = it } != null) {
                val l = line ?: continue
                if (!l.startsWith("data: ")) continue
                val data = l.removePrefix("data: ").trim()
                if (data.isEmpty() || data == "[DONE]") continue

                try {
                    val event = JSONObject(data)
                    when (event.optString("type", "")) {
                        "content_block_start" -> {
                            currentBlockIndex = event.optInt("index", -1)
                            val contentBlock = event.optJSONObject("content_block")
                            currentBlockType = contentBlock?.optString("type", "") ?: ""
                            if (currentBlockType == "tool_use") {
                                val toolId = contentBlock?.optString("id", "") ?: ""
                                val toolName = contentBlock?.optString("name", "") ?: ""
                                toolCallIds[currentBlockIndex] = toolId
                                toolCallNames[currentBlockIndex] = toolName
                                toolInputBuffers[currentBlockIndex] = StringBuilder()
                                trySend(StreamEvent.ToolCallStart(toolId, toolName))
                            }
                        }
                        "content_block_delta" -> {
                            val index = event.optInt("index", currentBlockIndex)
                            val delta = event.optJSONObject("delta") ?: continue
                            when (delta.optString("type", "")) {
                                "text_delta" -> {
                                    val text = delta.optString("text", "")
                                    if (text.isNotEmpty()) { fullText.append(text); trySend(StreamEvent.Token(text)) }
                                }
                                "thinking_delta" -> {
                                    val thinking = delta.optString("thinking", "")
                                    if (thinking.isNotEmpty()) { thinkingText.append(thinking); trySend(StreamEvent.Thinking(thinking)) }
                                }
                                "input_json_delta" -> {
                                    val partialJson = delta.optString("partial_json", "")
                                    if (partialJson.isNotEmpty()) {
                                        toolInputBuffers[index]?.append(partialJson)
                                        val toolId = toolCallIds[index] ?: ""
                                        trySend(StreamEvent.ToolCallInput(toolId, partialJson))
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
                                val inputJson = try { JSONObject(inputBuffer.toString()) } catch (e: Exception) { JSONObject() }
                                toolCalls.add(LlmToolCall(toolId, toolName, inputJson))
                                trySend(StreamEvent.ToolCallEnd(toolId, toolName, inputJson))
                            }
                        }
                        "message_stop" -> {
                            trySend(StreamEvent.Done(fullText.toString(), toolCalls.toList()))
                        }
                        "error" -> {
                            val errorObj = event.optJSONObject("error")
                            val errorMsg = errorObj?.optString("message", "Unknown error") ?: "Unknown error"
                            Log.e(TAG, "Stream error event: $errorMsg")
                            trySend(StreamEvent.Error(errorMsg))
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Error parsing SSE event: ${e.message}")
                }
            }
            reader.close()
            response.close()
        } catch (e: Exception) {
            Log.e(TAG, "Stream error: ${e.message}", e)
            trySend(StreamEvent.Error("${e.javaClass.simpleName}: ${e.message ?: "Connection failed"}"))
        }
        close()
        awaitClose()
    }

    private fun buildRequestBody(messages: List<ConversationMessage>, tools: List<JSONObject>, stream: Boolean): String {
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

    private fun groupMessagesForAnthropic(messages: List<ConversationMessage>): List<JSONObject> {
        val result = mutableListOf<JSONObject>()
        var i = 0
        while (i < messages.size) {
            val msg = messages[i]
            when (msg.role) {
                "user" -> {
                    val msgObj = JSONObject()
                    msgObj.put("role", "user")
                    val contentArray = JSONArray()
                    val textBlock = JSONObject().apply { put("type", "text"); put("text", msg.content) }
                    contentArray.put(textBlock)
                    msgObj.put("content", contentArray)
                    result.add(msgObj)
                }
                "assistant" -> {
                    val msgObj = JSONObject()
                    msgObj.put("role", "assistant")
                    val contentArray = JSONArray()
                    if (msg.content.isNotEmpty()) {
                        contentArray.put(JSONObject().apply { put("type", "text"); put("text", msg.content) })
                    }
                    msgObj.put("content", contentArray)
                    result.add(msgObj)
                }
                "tool" -> {
                    val msgObj = JSONObject()
                    msgObj.put("role", "user")
                    val contentArray = JSONArray()
                    contentArray.put(JSONObject().apply {
                        put("type", "tool_result")
                        put("tool_use_id", msg.toolCallId ?: "")
                        put("content", msg.content)
                    })
                    var j = i + 1
                    while (j < messages.size && messages[j].role == "tool") {
                        val nextTool = messages[j]
                        contentArray.put(JSONObject().apply {
                            put("type", "tool_result")
                            put("tool_use_id", nextTool.toolCallId ?: "")
                            put("content", nextTool.content)
                        })
                        j++
                    }
                    msgObj.put("content", contentArray)
                    result.add(msgObj)
                    i = j - 1
                }
            }
            i++
        }
        return result
    }

    private fun convertToolToAnthropicFormat(tool: JSONObject): JSONObject {
        val t = JSONObject()
        t.put("name", tool.optString("name", ""))
        t.put("description", tool.optString("description", ""))
        val schema = tool.optJSONObject("input_schema")
            ?: tool.optJSONObject("parameters")
            ?: JSONObject().apply { put("type", "object"); put("properties", JSONObject()) }
        t.put("input_schema", schema)
        return t
    }

    private fun buildRequest(body: String): Request = Request.Builder()
        .url(BASE_URL)
        .addHeader("x-api-key", apiKey)
        .addHeader("anthropic-version", API_VERSION)
        .addHeader("content-type", "application/json")
        .post(body.toRequestBody(JSON_MEDIA_TYPE))
        .build()

    private fun parseFullResponse(json: JSONObject): LlmResponse {
        val contentArray = json.optJSONArray("content") ?: JSONArray()
        val stopReason = json.optString("stop_reason", "stop")
        val textParts = StringBuilder()
        val thinkingParts = StringBuilder()
        val toolCalls = mutableListOf<LlmToolCall>()

        for (i in 0 until contentArray.length()) {
            val block = contentArray.getJSONObject(i)
            when (block.optString("type", "")) {
                "text"     -> textParts.append(block.optString("text", ""))
                "thinking" -> thinkingParts.append(block.optString("thinking", ""))
                "tool_use" -> toolCalls.add(LlmToolCall(
                    id = block.optString("id", ""),
                    name = block.optString("name", ""),
                    input = block.optJSONObject("input") ?: JSONObject()
                ))
            }
        }
        return LlmResponse(text = textParts.toString(), thinking = thinkingParts.toString(),
            toolCalls = toolCalls, finishReason = stopReason)
    }
}
