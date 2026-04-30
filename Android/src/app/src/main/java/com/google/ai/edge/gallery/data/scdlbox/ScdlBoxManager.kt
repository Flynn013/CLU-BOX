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
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequest
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

private const val TAG = "ScdlBoxManager"

/** Minimum repeat interval enforced by WorkManager (15 minutes). */
private const val MIN_INTERVAL_MINUTES = 15L

/**
 * Manages the WorkManager scheduling lifecycle for [ScdlBoxEntity] tasks.
 *
 * Each enabled task gets a uniquely-named [PeriodicWorkRequest] whose tag
 * matches the task's UUID.  Disabling or deleting a task cancels the
 * corresponding work by unique name.
 *
 * WorkManager persists requests across device reboots automatically, so no
 * `BOOT_COMPLETED` receiver is required.
 */
class ScdlBoxManager(context: Context) {

  private val workManager = WorkManager.getInstance(context)

  /**
   * Schedule (or re-schedule) a periodic work request for [task].
   *
   * If the task already has a scheduled request (same unique name), it is
   * replaced using [ExistingPeriodicWorkPolicy.REPLACE] so that interval
   * changes take effect immediately.
   *
   * No-ops if [task.isEnabled] is false — callers should call [cancel] instead.
   */
  fun schedule(task: ScdlBoxEntity) {
    if (!task.isEnabled) {
      Log.d(TAG, "schedule: task '${task.title}' is disabled — skipping")
      return
    }

    val intervalMinutes = task.intervalMinutes.coerceAtLeast(MIN_INTERVAL_MINUTES)
    Log.d(TAG, "schedule: '${task.title}' every ${intervalMinutes}m")

    val inputData = Data.Builder()
      .putString(ScdlBoxWorker.KEY_TASK_ID, task.id)
      .build()

    val request = PeriodicWorkRequest.Builder(
      ScdlBoxWorker::class.java,
      intervalMinutes,
      TimeUnit.MINUTES,
    )
      .setInputData(inputData)
      .addTag(task.id)
      .build()

    workManager.enqueueUniquePeriodicWork(
      uniqueWorkName(task.id),
      ExistingPeriodicWorkPolicy.REPLACE,
      request,
    )
  }

  /**
   * Cancel the periodic work for [taskId].
   *
   * Safe to call even if no work is currently scheduled for this ID.
   */
  fun cancel(taskId: String) {
    Log.d(TAG, "cancel: taskId=$taskId")
    workManager.cancelUniqueWork(uniqueWorkName(taskId))
  }

  /**
   * Re-evaluate and schedule/cancel tasks after a toggle or edit.
   *
   * @param task The updated task entity.
   * @param wasEnabled Whether the task was enabled before the update.
   */
  fun onTaskUpdated(task: ScdlBoxEntity, wasEnabled: Boolean = true) {
    if (task.isEnabled) {
      schedule(task)
    } else {
      cancel(task.id)
    }
  }

  /** Deterministic unique work name for a given task UUID. */
  private fun uniqueWorkName(taskId: String) = "scdl_box_$taskId"
}
