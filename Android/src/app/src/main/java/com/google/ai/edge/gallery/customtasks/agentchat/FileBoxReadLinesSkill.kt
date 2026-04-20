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

import org.json.JSONObject

/**
 * CluSkill implementation for paginated file reading.
 *
 * Reads a specific range of lines from a file so the LLM can inspect
 * large outputs without consuming the entire context window.
 *
 * @param agentTools The [AgentTools] instance to delegate execution to.
 */
class FileBoxReadLinesSkill(private val agentTools: AgentTools) : CluSkill {

  override val name: String = "fileBoxReadLines"

  override val description: String =
    "Reads a line range from a file to save memory. Use when a file/log is too large."

  override val jsonSchema: String =
    """{"name":"fileBoxReadLines","parameters":{"file_path":{"type":"string"},"start_line":{"type":"integer"},"end_line":{"type":"integer"}},"required":["file_path","start_line","end_line"]}"""

  override val fewShotExample: String =
    """fileBoxReadLines(file_path="BrainBox/temp_out/spill_123.txt", start_line=0, end_line=50)"""

  override suspend fun execute(args: JSONObject): String {
    val filePath = args.optString("file_path", "")
    val startLine = args.optInt("start_line", 0)
    val endLine = args.optInt("end_line", 50)
    if (filePath.isBlank()) return "Error: file_path argument is required"
    val result = agentTools.fileBoxReadLines(filePath, startLine, endLine)
    return result.entries.joinToString(", ") { "${it.key}=${it.value}" }
  }
}
