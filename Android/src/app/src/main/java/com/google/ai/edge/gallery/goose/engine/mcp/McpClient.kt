/*
 * Copyright 2026 Flynn013 / CLU/BOX
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package com.google.ai.edge.gallery.goose.engine.mcp

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * MCP (Model Context Protocol) client.
 *
 * Implements the JSON-RPC 2.0 based MCP handshake and tool-calling protocol:
 *   1. `initialize` → server capabilities
 *   2. `notifications/initialized`
 *   3. `tools/list` → discover tools
 *   4. `tools/call` → invoke a tool
 *
 * Ported from MaxFlynn13/goose-android (engine/mcp/McpClient.kt).
 */
class McpClient(private val transport: McpTransport) {

    companion object {
        private const val TAG = "McpClient"
        private const val PROTOCOL_VERSION = "2024-11-05"
        private const val CLIENT_NAME = "clu-box"
        private const val CLIENT_VERSION = "1.0.0"
    }

    private var requestId = 0
    private val mutex = Mutex()
    private var serverCapabilities: JSONObject? = null

    /**
     * Open the transport, perform the MCP `initialize` handshake, and send the
     * `notifications/initialized` notification.
     */
    suspend fun connect(): Result<Unit> = runCatching {
        transport.start().getOrThrow()
        Log.d(TAG, "Transport started, sending initialize request")

        val initParams = JSONObject().apply {
            put("protocolVersion", PROTOCOL_VERSION)
            put("capabilities", JSONObject())
            put("clientInfo", JSONObject().apply {
                put("name", CLIENT_NAME)
                put("version", CLIENT_VERSION)
            })
        }

        val response = sendRequest("initialize", initParams)
        serverCapabilities = response.optJSONObject("result")
            ?.optJSONObject("capabilities")
        Log.d(TAG, "Server capabilities: $serverCapabilities")

        val notification = JSONObject().apply {
            put("jsonrpc", "2.0")
            put("method", "notifications/initialized")
        }
        transport.send(notification.toString())
        Log.d(TAG, "MCP session initialized")
    }

    /**
     * Ask the server for its tool catalogue via `tools/list`.
     */
    suspend fun listTools(): List<McpTool> {
        val response = sendRequest("tools/list", JSONObject())
        val result = response.optJSONObject("result") ?: return emptyList()
        val toolsArray: JSONArray = result.optJSONArray("tools") ?: return emptyList()

        return (0 until toolsArray.length()).map { i ->
            val obj = toolsArray.getJSONObject(i)
            McpTool(
                name = obj.getString("name"),
                description = obj.optString("description", ""),
                inputSchema = obj.optJSONObject("inputSchema") ?: JSONObject()
            )
        }
    }

    /**
     * Execute a tool on the server via `tools/call`.
     */
    suspend fun callTool(name: String, arguments: JSONObject): McpToolResult {
        val params = JSONObject().apply {
            put("name", name)
            put("arguments", arguments)
        }
        val response = sendRequest("tools/call", params)

        response.optJSONObject("error")?.let { err ->
            val msg = err.optString("message", "Unknown MCP error")
            Log.e(TAG, "tools/call error: $msg")
            return McpToolResult(content = msg, isError = true)
        }

        val result = response.optJSONObject("result")
        val isError = result?.optBoolean("isError", false) ?: false

        val contentArray: JSONArray? = result?.optJSONArray("content")
        val text = if (contentArray != null && contentArray.length() > 0) {
            (0 until contentArray.length()).joinToString("\n") { i ->
                val piece = contentArray.getJSONObject(i)
                piece.optString("text", piece.toString())
            }
        } else {
            result?.toString() ?: ""
        }

        return McpToolResult(content = text, isError = isError)
    }

    /**
     * Gracefully disconnect: close the transport.
     */
    suspend fun disconnect() {
        try {
            transport.close()
            Log.d(TAG, "Disconnected")
        } catch (e: Exception) {
            Log.w(TAG, "Error during disconnect", e)
        }
    }

    private suspend fun sendRequest(method: String, params: JSONObject?): JSONObject =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val id = ++requestId
                val envelope = JSONObject().apply {
                    put("jsonrpc", "2.0")
                    put("id", id)
                    put("method", method)
                    if (params != null) put("params", params)
                }

                Log.d(TAG, "→ $method (id=$id)")
                transport.send(envelope.toString())

                while (true) {
                    val raw = transport.receive()
                    val msg = try {
                        JSONObject(raw)
                    } catch (e: Exception) {
                        Log.w(TAG, "Non-JSON message from server, skipping: $raw")
                        continue
                    }

                    if (!msg.has("id")) {
                        val notifMethod = msg.optString("method", "<unknown>")
                        Log.d(TAG, "← notification: $notifMethod")
                        continue
                    }

                    val responseId = msg.optInt("id", -1)
                    if (responseId == id) {
                        Log.d(TAG, "← response for $method (id=$id)")
                        return@withContext msg
                    }

                    Log.w(TAG, "Unexpected response id=$responseId while waiting for id=$id")
                }

                @Suppress("UNREACHABLE_CODE")
                throw IllegalStateException("Unreachable")
            }
        }
}

// ── Models ────────────────────────────────────────────────────────────────────

data class McpTool(
    val name: String,
    val description: String,
    val inputSchema: JSONObject
)

data class McpToolResult(
    val content: String,
    val isError: Boolean = false
)
