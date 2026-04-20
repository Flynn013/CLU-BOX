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
 * CluSkill implementation for keyword search within files.
 *
 * Scans a file for a keyword and returns matching lines with ±2 lines
 * of surrounding context — like a lightweight `grep -C 2`.
 *
 * @param agentTools The [AgentTools] instance to delegate execution to.
 */
class BrainBoxGrepSkill(private val agentTools: AgentTools) : CluSkill {

  override val name: String = "brainBoxGrep"

  override val description: String =
    "Searches a file for a keyword and returns surrounding lines."

  override val jsonSchema: String =
    """{"name":"brainBoxGrep","parameters":{"file_path":{"type":"string"},"keyword":{"type":"string"}},"required":["file_path","keyword"]}"""

  override val fewShotExample: String =
    """brainBoxGrep(file_path="BrainBox/temp_out/spill_123.txt", keyword="Exception")"""

  override suspend fun execute(args: JSONObject): String {
    val filePath = args.optString("file_path", "")
    val keyword = args.optString("keyword", "")
    if (filePath.isBlank()) return "Error: file_path argument is required"
    if (keyword.isBlank()) return "Error: keyword argument is required"
    val result = agentTools.brainBoxGrep(filePath, keyword)
    return result.entries.joinToString(", ") { "${it.key}=${it.value}" }
  }
}
