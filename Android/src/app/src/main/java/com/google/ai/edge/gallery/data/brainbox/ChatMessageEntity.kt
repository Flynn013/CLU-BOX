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

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Persists a single user or agent turn from the CLU/BOX chat interface.
 *
 * @param id          Auto-generated row id.
 * @param taskId      The task this message belongs to (e.g. "llm_chat").
 * @param modelName   The name of the model associated with this conversation.
 * @param side        Either "USER" or "AGENT".
 * @param content     The raw text content of the message.
 * @param timestampMs Unix epoch milliseconds at the time of insertion.
 */
@Entity(tableName = "chat_history")
data class ChatMessageEntity(
  @PrimaryKey(autoGenerate = true) val id: Long = 0,
  val taskId: String,
  val modelName: String,
  val side: String,
  val content: String,
  val timestampMs: Long,
)
