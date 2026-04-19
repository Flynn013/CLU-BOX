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
    "Executes bash commands in Termux. STRICTLY PROHIBITED FOR FILE CREATION. Use only to run/test code or debug."

  override val jsonSchema: String = """
    {
      "name": "shellExecute",
      "parameters": {
        "command": {
          "type": "string",
          "description": "The shell command to execute. NEVER use echo/cat/nano/tee/sed to write files."
        }
      },
      "required": ["command"]
    }
  """.trimIndent()

  override val fewShotExample: String = """
    Example — Run a Python script:
      shellExecute(command="python3 /data/user/0/com.google.ai.edge.gallery/files/clu_file_box/my_app/script.py")
    Example — Check directory listing:
      shellExecute(command="ls -la")
  """.trimIndent()

  override suspend fun execute(args: JSONObject): String {
    val command = args.optString("command", "")
    if (command.isBlank()) return "Error: command argument is required"
    val result = agentTools.shellExecute(command)
    return result.entries.joinToString(", ") { "${it.key}=${it.value}" }
  }
}
