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
 * CluSkill implementation for the FileBox_Write tool.
 *
 * Wraps the [AgentTools.fileBoxWrite] method with structured metadata
 * for system prompt injection. The [execute] method delegates to the
 * provided [AgentTools] instance.
 *
 * @param agentTools The [AgentTools] instance to delegate execution to.
 */
class FileBoxWriteSkill(private val agentTools: AgentTools) : CluSkill {

  override val name: String = "fileBoxWrite"

  override val description: String =
    "Create or overwrite a file in the FileBox workspace. " +
    "The ONLY tool for file creation and editing — never use shellExecute redirection. " +
    "Parent directories are created automatically."

  override val jsonSchema: String = """
    {
      "name": "fileBoxWrite",
      "description": "Create or overwrite a file in the sandboxed FileBox workspace.",
      "parameters": {
        "type": "object",
        "properties": {
          "file_path": {
            "type": "string",
            "description": "Relative path from workspace root, e.g. 'src/main.py'. Parent dirs auto-created."
          },
          "content": {
            "type": "string",
            "description": "Full file content to write. Replaces any existing file entirely."
          }
        },
        "required": ["file_path", "content"]
      }
    }
  """.trimIndent()

  override val fewShotExample: String =
    """fileBoxWrite(file_path="my_app/script.py", content="print('hello world')")"""

  override suspend fun execute(args: JSONObject): String {
    val filePath = args.optString("file_path", "")
    val content = args.optString("content", "")
    if (filePath.isBlank()) return "Error: file_path argument is required"
    if (content.isBlank()) return "Error: content argument is required"
    val result = agentTools.fileBoxWrite(filePath, content)
    return result.entries.joinToString(", ") { "${it.key}=${it.value}" }
  }
}
