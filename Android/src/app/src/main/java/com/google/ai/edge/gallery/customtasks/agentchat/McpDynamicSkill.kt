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

package com.google.ai.edge.gallery.customtasks.agentchat

import com.google.ai.edge.gallery.data.mcp.McpClient
import com.google.ai.edge.gallery.data.mcp.McpTool
import org.json.JSONObject

/**
 * A [CluSkill] that wraps a single MCP tool loaded dynamically at runtime.
 *
 * When the agent loop dispatches this skill, [execute] forwards the LLM-generated arguments
 * to [McpClient.callTool] over the live stdio connection and returns the server's response
 * as a plain string for re-injection into the context window.
 *
 * Instances are created by [com.google.ai.edge.gallery.GalleryApp] inside the
 * `onConnectionReady` callback from
 * [com.google.ai.edge.gallery.data.mcp.McpConnectionManager] and immediately registered via
 * [SkillRegistry.registerDynamicSkills].
 *
 * @param tool   The [McpTool] descriptor returned by the server's `tools/list` response.
 * @param client The live [McpClient] connected to the server that owns this tool.
 */
class McpDynamicSkill(
  private val tool: McpTool,
  private val client: McpClient,
) : CluSkill {

  override val name: String = tool.name

  /** Trimmed to 200 chars to keep the system-prompt token count manageable. */
  override val description: String = tool.description.take(200)

  override val jsonSchema: String = buildJsonSchema(tool)

  override val fewShotExample: String = "${tool.name}(${buildExampleArgs(tool.inputSchema)})"

  override suspend fun execute(args: JSONObject): String = client.callTool(tool.name, args)

  // ── Private helpers ────────────────────────────────────────────────────────

  /**
   * Derives a compact JSON schema string from the MCP tool's `inputSchema`.
   *
   * The schema is formatted as a single-line JSON object compatible with the pattern
   * expected by [SkillRegistry.buildFinalSystemPrompt]:
   * `{"name":"<name>","parameters":{…},"required":[…]}`
   */
  private fun buildJsonSchema(tool: McpTool): String {
    val schema = JSONObject()
    schema.put("name", tool.name)
    val properties = tool.inputSchema.optJSONObject("properties") ?: JSONObject()
    schema.put("parameters", properties)
    val required = tool.inputSchema.optJSONArray("required")
    if (required != null) schema.put("required", required)
    return schema.toString()
  }

  /**
   * Builds a minimal illustrative argument string for the few-shot example
   * by taking up to the first two property names from the input schema.
   */
  private fun buildExampleArgs(inputSchema: JSONObject): String {
    val properties = inputSchema.optJSONObject("properties") ?: return ""
    return properties.keys().asSequence().take(2).joinToString(", ") { key -> "$key=\"...\"" }
  }
}
