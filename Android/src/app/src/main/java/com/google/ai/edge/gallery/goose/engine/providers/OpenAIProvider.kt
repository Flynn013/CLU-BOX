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
 * OpenAI provider with full function-calling and SSE streaming support.
 * Compatible with OpenAI API and any OpenAI-compatible endpoint.
 *
 * Ported from MaxFlynn13/goose-android (engine/providers/OpenAIProvider.kt).
 */
class OpenAIProvider(
    private val apiKey: String,
    override val modelId: String,
    private val baseUrl: String = "https://api.openai.com/v1"
) : LlmProvider {

    override val providerId: String = "openai"

    private val client = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    companion object {
        private const val TAG = "OpenAIProvider"
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
            val toolCalls = mutableListOf<LlmToolCall>()
            val toolCallIds = mutableMapOf<Int, String>()
            val toolCallNames = mutableMapOf<Int, String>()
            val toolInputBuffers = mutableMapOf<Int, StringBuilder>()

            var line: String?
            while (reader.readLine().also { line = it } != null) {
                val l = line ?: continue
                if (!l.startsWith("data: ")) continue
                val data = l.removePrefix("data: ").trim()
                if (data.isEmpty() || data == "[DONE]") {
                    if (data == "[DONE]") {
                        for ((index, inputBuffer) in toolInputBuffers) {
                            val id = toolCallIds[index] ?: ""
                            val name = toolCallNames[index] ?: ""
                            val inputJson = try { JSONObject(inputBuffer.toString()) } catch (e: Exception) { JSONObject() }
                            toolCalls.add(LlmToolCall(id, name, inputJson))
                            trySend(StreamEvent.ToolCallEnd(id, name, inputJson))
                        }
                        trySend(StreamEvent.Done(fullText.toString(), toolCalls.toList()))
                    }
                    continue
                }

                try {
                    val chunk = JSONObject(data)
                    val choices = chunk.optJSONArray("choices")
                    if (choices == null || choices.length() == 0) continue

                    val choice = choices.getJSONObject(0)
                    val delta = choice.optJSONObject("delta") ?: continue

                    val content = delta.optString("content", "")
                    if (content.isNotEmpty()) { fullText.append(content); trySend(StreamEvent.Token(content)) }

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
                                trySend(StreamEvent.ToolCallStart(id, name))
                            }
                            val args = function?.optString("arguments", "") ?: ""
                            if (args.isNotEmpty()) {
                                toolInputBuffers[index]?.append(args)
                                val toolId = toolCallIds[index] ?: ""
                                trySend(StreamEvent.ToolCallInput(toolId, args))
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Error parsing SSE chunk: ${e.message}")
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
        body.put("stream", stream)

        val messagesArray = JSONArray()
        for (msg in messages) {
            val msgObj = JSONObject()
            when (msg.role) {
                "system"    -> { msgObj.put("role", "system"); msgObj.put("content", msg.content) }
                "user"      -> { msgObj.put("role", "user"); msgObj.put("content", msg.content) }
                "assistant" -> { msgObj.put("role", "assistant"); if (msg.content.isNotEmpty()) msgObj.put("content", msg.content) }
                "tool"      -> { msgObj.put("role", "tool"); msgObj.put("content", msg.content); msgObj.put("tool_call_id", msg.toolCallId ?: "") }
                else        -> { msgObj.put("role", msg.role); msgObj.put("content", msg.content) }
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
        val t = JSONObject()
        t.put("type", "function")
        val function = JSONObject()
        function.put("name", tool.optString("name", ""))
        function.put("description", tool.optString("description", ""))
        val schema = tool.optJSONObject("parameters")
            ?: tool.optJSONObject("input_schema")
            ?: JSONObject().apply { put("type", "object"); put("properties", JSONObject()) }
        function.put("parameters", schema)
        t.put("function", function)
        return t
    }

    private fun buildRequest(body: String): Request = Request.Builder()
        .url("$baseUrl/chat/completions")
        .addHeader("Authorization", "Bearer $apiKey")
        .addHeader("Content-Type", "application/json")
        .post(body.toRequestBody(JSON_MEDIA_TYPE))
        .build()

    private fun parseFullResponse(json: JSONObject): LlmResponse {
        val choices = json.optJSONArray("choices")
        if (choices == null || choices.length() == 0) return LlmResponse(text = "", finishReason = "error")

        val choice = choices.getJSONObject(0)
        val message = choice.optJSONObject("message") ?: JSONObject()
        val finishReason = choice.optString("finish_reason", "stop")
        val text = message.optString("content", "") ?: ""
        val toolCalls = mutableListOf<LlmToolCall>()

        val toolCallsArray = message.optJSONArray("tool_calls")
        if (toolCallsArray != null) {
            for (i in 0 until toolCallsArray.length()) {
                val tc = toolCallsArray.getJSONObject(i)
                val function = tc.optJSONObject("function")
                val argsStr = function?.optString("arguments", "{}") ?: "{}"
                toolCalls.add(LlmToolCall(
                    id = tc.optString("id", ""),
                    name = function?.optString("name", "") ?: "",
                    input = try { JSONObject(argsStr) } catch (e: Exception) { JSONObject() }
                ))
            }
        }
        return LlmResponse(text = text, toolCalls = toolCalls, finishReason = finishReason)
    }
}
