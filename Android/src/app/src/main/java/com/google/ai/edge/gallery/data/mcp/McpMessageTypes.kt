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

import org.json.JSONArray
import org.json.JSONObject

// ─────────────────────────────────────────────────────────────────────────────
// JSON-RPC 2.0 wire types
// ─────────────────────────────────────────────────────────────────────────────

/** JSON-RPC 2.0 request message. */
data class JsonRpcRequest(
  val id: Int,
  val method: String,
  val params: JSONObject? = null,
) {
  fun toJson(): String {
    val obj = JSONObject()
    obj.put("jsonrpc", "2.0")
    obj.put("id", id)
    obj.put("method", method)
    if (params != null) obj.put("params", params)
    return obj.toString()
  }
}

/** JSON-RPC 2.0 response message (result **or** error). */
data class JsonRpcResponse(
  val id: Int?,
  val result: JSONObject?,
  val error: JSONObject?,
) {
  companion object {
    fun fromJson(line: String): JsonRpcResponse? =
      try {
        val obj = JSONObject(line)
        val id = if (obj.has("id") && !obj.isNull("id")) obj.getInt("id") else null
        val result =
          if (obj.has("result") && !obj.isNull("result")) obj.getJSONObject("result") else null
        val error =
          if (obj.has("error") && !obj.isNull("error")) obj.getJSONObject("error") else null
        JsonRpcResponse(id, result, error)
      } catch (_: Exception) {
        null
      }
  }
}

// ─────────────────────────────────────────────────────────────────────────────
// MCP domain types
// ─────────────────────────────────────────────────────────────────────────────

/** One tool entry returned by a `tools/list` response. */
data class McpTool(
  val name: String,
  val description: String,
  val inputSchema: JSONObject,
)

/** Persisted configuration for a single MCP server. */
data class McpServerConfig(
  val name: String,
  val command: String,
  val envVars: Map<String, String> = emptyMap(),
)

/** Runtime connection state of an MCP server. */
enum class McpConnectionState {
  IDLE,
  CONNECTING,
  CONNECTED,
  ERROR,
}

/** Combines [McpServerConfig] with its live [McpConnectionState] and loaded [tools]. */
data class McpConnectionStatus(
  val config: McpServerConfig,
  val state: McpConnectionState,
  val tools: List<McpTool> = emptyList(),
  val errorMessage: String = "",
)
