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

package com.google.ai.edge.gallery.data.scdlbox

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.google.ai.edge.gallery.data.TerminalSessionManager
import com.google.ai.edge.gallery.data.brainbox.GraphDatabase

private const val TAG = "ScdlBoxWorker"

/**
 * WorkManager worker that executes a single [ScdlBoxEntity] when its
 * [PeriodicWorkRequest][androidx.work.PeriodicWorkRequest] fires.
 *
 * Execution logic:
 * - If [ScdlBoxEntity.isShellCommand] is `true`, the payload is sent to
 *   [TerminalSessionManager] (the same PRoot sandbox used by the agent).
 * - If `false`, the natural-language payload is logged as a pending LLM
 *   prompt stub.  Full headless LLM dispatch will be wired in a future
 *   session once the inference engine exposes a headless API.
 *
 * All execution results are written to Android logcat under the [TAG]
 * `"ScdlBoxWorker"`.  Because [LogBoxManager] captures all logcat by
 * default, these entries appear automatically in the MSTR_CTRL
 * diagnostics panel without needing a direct reference to [LogBoxManager].
 */
class ScdlBoxWorker(
  private val appContext: Context,
  workerParams: WorkerParameters,
) : CoroutineWorker(appContext, workerParams) {

  companion object {
    /** Input data key carrying the task UUID. Set by [ScdlBoxManager.schedule]. */
    const val KEY_TASK_ID = "task_id"
  }

  override suspend fun doWork(): Result {
    val taskId = inputData.getString(KEY_TASK_ID)
      ?: return Result.failure().also {
        Log.e(TAG, "doWork: missing task_id input — cannot execute")
      }

    // Fetch the task from the DB.  It may have been deleted or disabled since
    // this request was last enqueued; guard against both scenarios.
    val db = GraphDatabase.getInstance(appContext)
    val task = db.scdlBoxDao().getById(taskId)

    if (task == null) {
      Log.w(TAG, "doWork: task $taskId not found — assuming deleted, nothing to do")
      return Result.success()
    }

    if (!task.isEnabled) {
      Log.d(TAG, "doWork: task '${task.title}' is disabled — skipping")
      return Result.success()
    }

    Log.i(TAG, "[SCDL_BOX] Executing Task: '${task.title}' | isShell=${task.isShellCommand}")

    return try {
      if (task.isShellCommand) {
        executeShellTask(task)
      } else {
        executeLlmTask(task)
      }
    } catch (e: Exception) {
      Log.e(TAG, "[SCDL_BOX] Task '${task.title}' threw an exception: ${e.message}", e)
      Result.failure()
    }
  }

  // ── Shell execution path ──────────────────────────────────────────────

  private fun executeShellTask(task: ScdlBoxEntity): Result {
    val tsm = TerminalSessionManager(appContext)
    return try {
      val (exitCode, output) = tsm.executeCommandWithExitCode(task.payload)
      val truncated = output.take(500)
      if (exitCode == 0) {
        Log.i(TAG, "[SCDL_BOX] Executed: '${task.title}' | Status: Success | Output: $truncated")
        Result.success()
      } else {
        Log.w(TAG, "[SCDL_BOX] Executed: '${task.title}' | Status: Failed (exit=$exitCode) | Output: $truncated")
        // Return success to WorkManager so it doesn't back-off;
        // the failure is informational, not a scheduling error.
        Result.success()
      }
    } catch (e: Exception) {
      Log.e(TAG, "[SCDL_BOX] Shell execution failed for '${task.title}': ${e.message}", e)
      Result.failure()
    }
  }

  // ── LLM dispatch path (stub) ──────────────────────────────────────────

  /**
   * Placeholder for headless LLM prompt dispatch.
   *
   * In the current architecture the LiteRT inference engine requires an
   * active Compose session to hold the model instance.  Full headless
   * dispatch will be wired when the engine exposes a process-level API.
   *
   * For now, the pending prompt is logged so the operator can see it in
   * the MSTR_CTRL diagnostic panel.
   */
  private fun executeLlmTask(task: ScdlBoxEntity): Result {
    Log.i(
      TAG,
      "[SCDL_BOX] LLM Task '${task.title}' queued (headless dispatch pending): ${task.payload.take(200)}",
    )
    return Result.success()
  }
}
