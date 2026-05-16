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
 * CluSkill implementation for the Shell_Execute tool.
 *
 * Wraps the [AgentTools.shellExecute] method with structured metadata
 * for system prompt injection. The [execute] method delegates to the
 * provided [AgentTools] instance.
 *
 * @param agentTools The [AgentTools] instance to delegate execution to.
 */
class ShellExecuteSkill(private val agentTools: AgentTools) : CluSkill {

  override val name: String = "shellExecute"

  override val description: String =
    "Run POSIX shell commands via BusyBox sh. " +
    "Use for OS operations, listing files, running binaries. " +
    "PROHIBITED for file creation or editing — use fileBoxWrite instead."

  override val jsonSchema: String = """
    {
      "name": "shellExecute",
      "description": "Run a POSIX shell command via BusyBox sh. Not for file writes — use fileBoxWrite.",
      "parameters": {
        "type": "object",
        "properties": {
          "command": {
            "type": "string",
            "description": "POSIX sh command. BusyBox only — no bash arrays, process substitution, or bash-isms."
          }
        },
        "required": ["command"]
      }
    }
  """.trimIndent()

  override val fewShotExample: String =
    """shellExecute(command="ls /data/user/0/com.google.ai.edge.gallery/files/clu_file_box/")"""

  override suspend fun execute(args: JSONObject): String {
    val command = args.optString("command", "")
    if (command.isBlank()) return "Error: command argument is required"
    val result = agentTools.shellExecute(command)
    return result.entries.joinToString(", ") { "${it.key}=${it.value}" }
  }
}
