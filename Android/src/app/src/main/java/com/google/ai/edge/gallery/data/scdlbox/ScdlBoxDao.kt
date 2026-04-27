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

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/**
 * Room Data Access Object for [ScdlBoxEntity].
 *
 * All write operations are `suspend` functions; [observeAll] returns a live [Flow]
 * suitable for Compose state observation.
 */
@Dao
interface ScdlBoxDao {

  /** Observe all tasks, ordered by title. Re-emits on every DB change. */
  @Query("SELECT * FROM scdl_tasks ORDER BY title ASC")
  fun observeAll(): Flow<List<ScdlBoxEntity>>

  /** One-shot read of all tasks. Used by [ScdlBoxWorker] on a background thread. */
  @Query("SELECT * FROM scdl_tasks")
  suspend fun getAll(): List<ScdlBoxEntity>

  /** Fetch a single task by ID, or null if not found. */
  @Query("SELECT * FROM scdl_tasks WHERE id = :id LIMIT 1")
  suspend fun getById(id: String): ScdlBoxEntity?

  /**
   * Insert a new task.
   * [OnConflictStrategy.REPLACE] lets callers upsert without a separate update call.
   */
  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun insert(task: ScdlBoxEntity)

  /** Update an existing task (matched by primary key). */
  @Update
  suspend fun update(task: ScdlBoxEntity)

  /** Delete a task by its primary key. */
  @Query("DELETE FROM scdl_tasks WHERE id = :id")
  suspend fun deleteById(id: String)

  /** Toggle the `isEnabled` flag on a single task. */
  @Query("UPDATE scdl_tasks SET isEnabled = :enabled WHERE id = :id")
  suspend fun setEnabled(id: String, enabled: Boolean)
}
