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
import com.google.ai.edge.gallery.data.brainbox.GraphDatabase
import com.google.ai.edge.gallery.data.scdlbox.ScdlBoxEntity
import com.google.ai.edge.gallery.data.scdlbox.ScdlBoxManager
import org.json.JSONObject
import java.util.UUID

private const val TAG = "ScdlBoxSkill"

/**
 * [CluSkill] that gives the LLM agent full CRUD control over SCDL_BOX
 * scheduled background tasks.
 *
 * ## Supported actions
 *
 * | `action` value | Description |
 * |---|---|
 * | `CREATE`  | Create a new recurring task |
 * | `UPDATE`  | Overwrite an existing task's fields |
 * | `DELETE`  | Remove a task by ID |
 * | `TOGGLE`  | Flip the `isEnabled` flag on an existing task |
 *
 * ## JSON schema arguments
 *
 * ```json
 * {
 *   "action": "CREATE | UPDATE | DELETE | TOGGLE",
 *   "taskId": "<uuid>",          // required for UPDATE / DELETE / TOGGLE
 *   "title": "...",              // required for CREATE
 *   "description": "...",        // optional
 *   "payload": "...",            // required for CREATE — shell command or LLM prompt
 *   "isShellCommand": true,      // required for CREATE
 *   "intervalMinutes": 60        // required for CREATE (min 15)
 * }
 * ```
 */
class ScdlBoxSkill(private val agentTools: AgentTools) : CluSkill {

  override val name: String = "scheduleTask"

  override val description: String =
    "Manage recurring background tasks in SCDL_BOX. " +
    "Actions: CREATE (new task), UPDATE (edit existing), DELETE (remove), TOGGLE (enable/disable). " +
    "Shell payloads are executed by the native PRoot sandbox. " +
    "LLM payloads are queued as headless prompts."

  override val jsonSchema: String = """
    {"name":"scheduleTask","parameters":{
      "action":{"type":"string","enum":["CREATE","UPDATE","DELETE","TOGGLE"]},
      "taskId":{"type":"string"},
      "title":{"type":"string"},
      "description":{"type":"string"},
      "payload":{"type":"string"},
      "isShellCommand":{"type":"boolean"},
      "intervalMinutes":{"type":"number"}
    },"required":["action"]}
  """.trimIndent()

  override val fewShotExample: String =
    """scheduleTask(action="CREATE", title="Health check", payload="uptime", isShellCommand=true, intervalMinutes=60)"""

  override suspend fun execute(args: JSONObject): String {
    val ctx = agentTools.context
      ?: return "[Error: Android Context not available]"

    val action = args.optString("action", "").uppercase()
    val db = GraphDatabase.getInstance(ctx)
    val dao = db.scdlBoxDao()
    val manager = ScdlBoxManager(ctx)

    return when (action) {
      "CREATE" -> {
        val title = args.optString("title", "").trim()
        val payload = args.optString("payload", "").trim()
        val isShellCommand = args.optBoolean("isShellCommand", true)
        val intervalMinutes = args.optLong("intervalMinutes", 60L)

        if (title.isBlank()) return "[Error: 'title' is required for CREATE]"
        if (payload.isBlank()) return "[Error: 'payload' is required for CREATE]"

        val task = ScdlBoxEntity(
          id = UUID.randomUUID().toString(),
          title = title,
          description = args.optString("description", ""),
          payload = payload,
          isShellCommand = isShellCommand,
          intervalMinutes = intervalMinutes.coerceAtLeast(15L),
          isEnabled = true,
        )
        dao.insert(task)
        manager.schedule(task)
        Log.d(TAG, "CREATE: task '${task.title}' id=${task.id}")
        "[SCDL_BOX] Task created: '${task.title}' (id=${task.id}) — runs every ${task.intervalMinutes}m"
      }

      "UPDATE" -> {
        val taskId = args.optString("taskId", "").trim()
        if (taskId.isBlank()) return "[Error: 'taskId' is required for UPDATE]"
        val existing = dao.getById(taskId) ?: return "[Error: Task '$taskId' not found]"

        val updated = existing.copy(
          title = args.optString("title", existing.title).takeIf { it.isNotBlank() } ?: existing.title,
          description = args.optString("description", existing.description),
          payload = args.optString("payload", existing.payload).takeIf { it.isNotBlank() } ?: existing.payload,
          isShellCommand = if (args.has("isShellCommand")) args.getBoolean("isShellCommand") else existing.isShellCommand,
          intervalMinutes = if (args.has("intervalMinutes")) args.getLong("intervalMinutes").coerceAtLeast(15L) else existing.intervalMinutes,
        )
        dao.update(updated)
        manager.onTaskUpdated(updated, wasEnabled = existing.isEnabled)
        "[SCDL_BOX] Task updated: '${updated.title}' (id=${updated.id})"
      }

      "DELETE" -> {
        val taskId = args.optString("taskId", "").trim()
        if (taskId.isBlank()) return "[Error: 'taskId' is required for DELETE]"
        val existing = dao.getById(taskId) ?: return "[Error: Task '$taskId' not found]"
        manager.cancel(taskId)
        dao.deleteById(taskId)
        "[SCDL_BOX] Task deleted: '${existing.title}' (id=$taskId)"
      }

      "TOGGLE" -> {
        val taskId = args.optString("taskId", "").trim()
        if (taskId.isBlank()) return "[Error: 'taskId' is required for TOGGLE]"
        val existing = dao.getById(taskId) ?: return "[Error: Task '$taskId' not found]"
        val newEnabled = !existing.isEnabled
        dao.setEnabled(taskId, newEnabled)
        val toggled = existing.copy(isEnabled = newEnabled)
        manager.onTaskUpdated(toggled, wasEnabled = existing.isEnabled)
        val state = if (newEnabled) "enabled" else "disabled"
        "[SCDL_BOX] Task '${ existing.title}' is now $state"
      }

      else -> "[Error: Unknown action '$action'. Valid actions: CREATE, UPDATE, DELETE, TOGGLE]"
    }
  }
}
