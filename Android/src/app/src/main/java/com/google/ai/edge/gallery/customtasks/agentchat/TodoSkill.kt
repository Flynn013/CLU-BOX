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

import android.util.Log
import java.io.File
import org.json.JSONObject

private const val TAG = "TodoSkill"
private const val TODO_FILE = "clu_todo_list.txt"

/**
 * CluSkill implementation for a file-backed Plan/To-Do checklist system.
 *
 * Supports four sub-commands via the `action` argument:
 *  - `add`    : Add a new item to the checklist.
 *  - `done`   : Mark item(s) matching a keyword as completed.
 *  - `list`   : Return the current checklist contents.
 *  - `clear`  : Delete all items from the checklist.
 *
 * The checklist is persisted to `clu_file_box/clu_todo_list.txt` inside the
 * app's private storage so it survives across turns and sessions.
 *
 * @param agentTools The [AgentTools] instance for context and sandbox access.
 */
class TodoSkill(private val agentTools: AgentTools) : CluSkill {

  override val name: String = "todo"

  override val description: String =
    "Manages a persistent to-do checklist. Actions: add, done, list, clear."

  override val jsonSchema: String =
    """{"name":"todo","parameters":{"action":{"type":"string","enum":["add","done","list","clear"]},"item":{"type":"string"}},"required":["action"]}"""

  override val fewShotExample: String =
    """todo(action="add", item="Write unit tests for auth module")"""

  override suspend fun execute(args: JSONObject): String {
    val action = args.optString("action", "").lowercase()
    val item = args.optString("item", "")
    val ctx = agentTools.context ?: return "Error: context not available"
    val todoFile = File(ctx.filesDir, "clu_file_box/$TODO_FILE")

    return when (action) {
      "add" -> {
        if (item.isBlank()) return "Error: 'item' argument required for action='add'"
        try {
          todoFile.parentFile?.mkdirs()
          todoFile.appendText("[ ] $item\n")
          Log.d(TAG, "Todo added: $item")
          "Added: $item"
        } catch (e: Exception) {
          Log.e(TAG, "Failed to add todo item", e)
          "Error: ${e.message}"
        }
      }
      "done" -> {
        if (item.isBlank()) return "Error: 'item' keyword required for action='done'"
        try {
          if (!todoFile.exists()) return "No todo list found."
          val lines = todoFile.readLines(Charsets.UTF_8)
          var matchCount = 0
          val updated = lines.map { line ->
            if (line.startsWith("[ ]") && line.contains(item, ignoreCase = true)) {
              matchCount++
              line.replaceFirst("[ ]", "[x]")
            } else {
              line
            }
          }
          todoFile.writeText(updated.joinToString("\n") + "\n")
          if (matchCount == 0) "No matching item found for: $item"
          else "Marked $matchCount item(s) as done."
        } catch (e: Exception) {
          Log.e(TAG, "Failed to mark todo done", e)
          "Error: ${e.message}"
        }
      }
      "list" -> {
        try {
          if (!todoFile.exists()) return "(empty todo list)"
          val contents = todoFile.readText(Charsets.UTF_8).trim()
          if (contents.isEmpty()) "(empty todo list)" else contents
        } catch (e: Exception) {
          Log.e(TAG, "Failed to list todos", e)
          "Error: ${e.message}"
        }
      }
      "clear" -> {
        try {
          todoFile.delete()
          "Todo list cleared."
        } catch (e: Exception) {
          Log.e(TAG, "Failed to clear todo list", e)
          "Error: ${e.message}"
        }
      }
      else -> "Error: unknown action '$action'. Use: add, done, list, clear."
    }
  }
}
