/*
 * Copyright 2026 Google LLC
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

package com.google.ai.edge.gallery.data.mcp

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicInteger

private const val TAG = "McpClient"
private const val READ_TIMEOUT_MS = 15_000L

/**
 * Implements the **Model Context Protocol** (MCP) client over the stdio transport provided by
 * [NativeMcpBridge].
 *
 * Protocol flow:
 * 1. [initialize] — sends `initialize`, receives `InitializeResult`, replies with
 *    `notifications/initialized`.
 * 2. [listTools] — sends `tools/list`, parses the returned tool schemas into [McpTool] objects.
 * 3. [callTool] — sends `tools/call` with the LLM-generated arguments; returns the server's
 *    text content response so it can be injected back into the context window.
 *
 * All network I/O is performed on [Dispatchers.IO] so callers may safely invoke these methods
 * from any coroutine context.
 */
class McpClient(private val bridge: NativeMcpBridge) {

  private val idCounter = AtomicInteger(1)

  private fun nextId(): Int = idCounter.getAndIncrement()

  // ── Core JSON-RPC transport ────────────────────────────────────────────────

  /**
   * Sends a JSON-RPC 2.0 request with [method] and optional [params], then reads lines from
   * the bridge until a response whose `id` matches the request arrives.
   *
   * Returns the `result` object on success, `null` on error or timeout.
   */
  private suspend fun sendAndReceive(method: String, params: JSONObject? = null): JSONObject? =
    withContext(Dispatchers.IO) {
      val id = nextId()
      val request = JsonRpcRequest(id, method, params)
      bridge.sendLine(request.toJson())

      // withTimeoutOrNull + runInterruptible ensures the blocking readLine() is interrupted
      // when the timeout expires, preventing the coroutine from hanging indefinitely.
      withTimeoutOrNull(READ_TIMEOUT_MS) {
        var result: JSONObject? = null
        while (result == null) {
          val line = runInterruptible { bridge.readLine() } ?: break
          if (line.isBlank()) continue
          val response = JsonRpcResponse.fromJson(line) ?: continue
          if (response.id == id) {
            if (response.error != null) {
              Log.e(TAG, "$method error: ${response.error}")
              break
            }
            result = response.result
          } else {
            // Notification or out-of-order message — log and skip.
            Log.d(TAG, "Skipping unexpected message: ${line.take(120)}")
          }
        }
        result
      }.also { result ->
        if (result == null) Log.e(TAG, "$method timed out or failed after ${READ_TIMEOUT_MS}ms")
      }
    }

  // ── MCP protocol methods ───────────────────────────────────────────────────

  /**
   * Performs the MCP handshake:
   * 1. Sends `initialize` with client metadata.
   * 2. Awaits the server's `InitializeResult`.
   * 3. Sends `notifications/initialized` (fire-and-forget, no id).
   *
   * @return `true` if the server acknowledged; `false` on failure.
   */
  suspend fun initialize(): Boolean {
    val params =
      JSONObject().apply {
        put("protocolVersion", "2024-11-05")
        put(
          "clientInfo",
          JSONObject().apply {
            put("name", "CLU-BOX")
            put("version", "1.0")
          },
        )
        put("capabilities", JSONObject())
      }
    val result = sendAndReceive("initialize", params) ?: return false
    Log.d(TAG, "initialize OK — serverInfo: ${result.optJSONObject("serverInfo")}")

    // Send the initialized notification (one-way, no response expected).
    withContext(Dispatchers.IO) {
      bridge.sendLine(
        JSONObject()
          .apply {
            put("jsonrpc", "2.0")
            put("method", "notifications/initialized")
          }
          .toString()
      )
    }
    return true
  }

  /**
   * Fetches the list of tools exposed by the connected MCP server.
   *
   * @return The list of [McpTool] entries, or an empty list on failure.
   */
  suspend fun listTools(): List<McpTool> {
    val result = sendAndReceive("tools/list") ?: return emptyList()
    val toolsArray: JSONArray = result.optJSONArray("tools") ?: return emptyList()
    return (0 until toolsArray.length()).map { i ->
      val obj = toolsArray.getJSONObject(i)
      McpTool(
        name = obj.optString("name"),
        description = obj.optString("description"),
        inputSchema = obj.optJSONObject("inputSchema") ?: JSONObject(),
      )
    }
  }

  /**
   * Calls a tool on the MCP server and returns its text content.
   *
   * The [arguments] JSON object is forwarded verbatim — callers should pass exactly
   * what the LLM produced for the tool's `parameters`.
   *
   * @param name Tool name (must match an entry from [listTools]).
   * @param arguments Parsed JSON arguments for the tool.
   * @return The server's text response, or an error string on failure.
   */
  suspend fun callTool(name: String, arguments: JSONObject): String {
    val params =
      JSONObject().apply {
        put("name", name)
        put("arguments", arguments)
      }
    val result =
      sendAndReceive("tools/call", params) ?: return "[MCP Error: no response from '$name']"

    // MCP result.content is an array of typed content blocks.
    val content = result.optJSONArray("content")
    if (content != null) {
      return buildString {
        for (i in 0 until content.length()) {
          val block = content.getJSONObject(i)
          when (block.optString("type")) {
            "text" -> append(block.optString("text"))
            else -> append(block.toString())
          }
        }
      }
    }
    return result.toString()
  }
}
