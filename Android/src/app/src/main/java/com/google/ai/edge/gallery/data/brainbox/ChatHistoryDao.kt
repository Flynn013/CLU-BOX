/*
 * Copyright 2025 Google LLC
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

package com.google.ai.edge.gallery.data.brainbox

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/** Data Access Object for CLU/BOX persistent chat history. */
@Dao
interface ChatHistoryDao {

  /** Persists a single chat message turn. */
  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun insertMessage(message: ChatMessageEntity)

  /** Retrieves all messages for a given task+model pair, ordered oldest-first. */
  @Query(
    "SELECT * FROM chat_history WHERE taskId = :taskId AND modelName = :modelName ORDER BY timestampMs ASC"
  )
  suspend fun getMessages(taskId: String, modelName: String): List<ChatMessageEntity>

  /** Deletes all messages for a given task+model pair ("Wipe Grid" operation). */
  @Query("DELETE FROM chat_history WHERE taskId = :taskId AND modelName = :modelName")
  suspend fun deleteMessages(taskId: String, modelName: String)

  /** Deletes every persisted chat message across all tasks and models. */
  @Query("DELETE FROM chat_history")
  suspend fun deleteAllMessages()

  /**
   * Returns the distinct (taskId, modelName) sessions with message count and latest timestamp.
   * Used by the chat history UI to show a list of previous conversations.
   */
  @Query(
    """
    SELECT taskId, modelName, COUNT(*) as messageCount, MAX(timestampMs) as lastTimestampMs
    FROM chat_history
    GROUP BY taskId, modelName
    ORDER BY lastTimestampMs DESC
    """
  )
  suspend fun getSessions(): List<ChatSessionSummary>
}
