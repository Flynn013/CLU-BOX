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

private const val TAG = "DelegateSkill"

/**
 * CluSkill that allows the agent to offload a sub-task to a background process.
 *
 * Two delegation modes are supported:
 *
 * ### In-session delegation (default — `isBackground = false`)
 * Sets [AgentTools.pendingTaskDescription] so the autonomous supervisor in
 * [AgentChatScreen] silently re-triggers inference with the task description
 * on the next turn.  The agent can "hand off" to itself and immediately move
 * to the next step while the supervisor handles the continuation.
 *
 * ### Background delegation (`isBackground = true`)
 * Creates a persistent [ScdlBoxEntity] in the Room database and schedules it
 * as a [PeriodicWorkRequest][androidx.work.PeriodicWorkRequest] via
 * [ScdlBoxManager].  This is a truly asynchronous dispatch — the task will
 * be executed by the SCDL_BOX WorkManager engine on its next scheduled
 * interval, even after the current chat session has ended.
 *
 * Background tasks are surfaced in the MSTR_CTRL LogBox so the operator
 * can track execution without needing an active chat session.
 *
 * @param agentTools The [AgentTools] instance providing context access.
 */
class DelegateSkill(private val agentTools: AgentTools) : CluSkill {

  override val name: String = "delegate"

  override val description: String =
    "Offloads a sub-task. " +
      "Set isBackground=false (default) to chain it in the current agentic loop. " +
      "Set isBackground=true to schedule it as a persistent SCDL_BOX WorkManager background task " +
      "that runs even after this session ends. " +
      "intervalMinutes sets the repeat interval (minimum 15, enforced by WorkManager)."

  override val jsonSchema: String =
    """{"name":"delegate","parameters":{
      "task":{"type":"string"},
      "isBackground":{"type":"boolean"},
      "isShellCommand":{"type":"boolean"},
      "intervalMinutes":{"type":"number","description":"Repeat interval in minutes. Minimum 15 (enforced by WorkManager)."}
    },"required":["task"]}"""

  override val fewShotExample: String =
    """delegate(task="Run the unit tests and report any failures")"""

  override suspend fun execute(args: JSONObject): String {
    val task = args.optString("task", "").trim()
    if (task.isBlank()) return "[Error: 'task' argument is required]"

    val isBackground = args.optBoolean("isBackground", false)

    return if (isBackground) {
      scheduleBackgroundTask(task, args)
    } else {
      // In-session: queue for the autonomous supervisor loop.
      agentTools.pendingTaskDescription = task
      Log.d(TAG, "In-session delegation: $task")
      "[Delegate] Sub-task queued for next autonomous iteration: $task"
    }
  }

  // ── Background scheduling via SCDL_BOX ───────────────────────────────────

  private suspend fun scheduleBackgroundTask(task: String, args: JSONObject): String {
    val ctx = agentTools.context
      ?: return "[Error: Android context not available for background scheduling]"

    val isShellCommand = args.optBoolean("isShellCommand", false)
    val intervalMinutes = args.optLong("intervalMinutes", 60L).coerceAtLeast(15L)

    val entity = ScdlBoxEntity(
      id = UUID.randomUUID().toString(),
      title = "Delegated: ${task.take(60)}",
      description = "Auto-created by agent delegation",
      payload = task,
      isShellCommand = isShellCommand,
      intervalMinutes = intervalMinutes,
      isEnabled = true,
    )

    return try {
      val db = GraphDatabase.getInstance(ctx)
      db.scdlBoxDao().insert(entity)
      ScdlBoxManager(ctx).schedule(entity)
      Log.d(TAG, "Background task scheduled: ${entity.id} — '${entity.title}'")
      "[SCDL_BOX] Background task scheduled: '${entity.title}' " +
        "| Runs every ${entity.intervalMinutes}m " +
        "| isShell=$isShellCommand " +
        "| id=${entity.id}"
    } catch (e: Exception) {
      Log.e(TAG, "Failed to schedule background task", e)
      "[Error: Failed to schedule background task — ${e.message}]"
    }
  }
}

