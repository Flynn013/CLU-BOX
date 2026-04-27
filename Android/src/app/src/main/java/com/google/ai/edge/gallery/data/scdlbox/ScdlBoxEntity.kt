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

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Persisted record representing a single SCDL_BOX recurring task.
 *
 * @param id              UUID primary key, assigned at creation time.
 * @param title           Short human-readable task name (≤80 chars).
 * @param description     Optional longer description of what the task does.
 * @param payload         Either a bash command (if [isShellCommand] == true) or a
 *                        natural-language LLM prompt (if false).
 * @param isShellCommand  When true, [payload] is executed via the native shell bridge.
 *                        When false, [payload] is dispatched as a headless LLM prompt.
 * @param intervalMinutes How often the task should repeat, in minutes (minimum 15 minutes
 *                        due to WorkManager's `PeriodicWorkRequest` floor).
 * @param isEnabled       Whether the task is actively scheduled in WorkManager.
 */
@Entity(tableName = "scdl_tasks")
data class ScdlBoxEntity(
  @PrimaryKey val id: String,
  val title: String,
  val description: String = "",
  val payload: String,
  val isShellCommand: Boolean,
  val intervalMinutes: Long,
  val isEnabled: Boolean = true,
)
