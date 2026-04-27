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
import org.json.JSONObject

private const val TAG = "DelegateSkill"

/**
 * CluSkill implementation for delegating sub-tasks to the autonomous agent loop.
 *
 * Calling this skill sets [AgentTools.pendingTaskDescription], which the autonomous
 * supervisor in [AgentChatScreen] picks up after the current inference turn completes
 * and re-triggers inference with the delegated task as the next input.
 *
 * This implements "delegation" by queuing the next work item in the agentic loop
 * without requiring user interaction — the model can spawn follow-up tasks autonomously.
 *
 * @param agentTools The [AgentTools] instance to set the pending task on.
 */
class DelegateSkill(private val agentTools: AgentTools) : CluSkill {

  override val name: String = "delegate"

  override val description: String =
    "Queues a follow-up sub-task for the autonomous loop. Use to chain multi-step work."

  override val jsonSchema: String =
    """{"name":"delegate","parameters":{"task":{"type":"string"}},"required":["task"]}"""

  override val fewShotExample: String =
    """delegate(task="Run the unit tests and report any failures")"""

  override suspend fun execute(args: JSONObject): String {
    val task = args.optString("task", "")
    if (task.isBlank()) return "Error: 'task' argument is required"

    agentTools.pendingTaskDescription = task
    Log.d(TAG, "Delegated sub-task: $task")
    return "Sub-task queued: $task"
  }
}
