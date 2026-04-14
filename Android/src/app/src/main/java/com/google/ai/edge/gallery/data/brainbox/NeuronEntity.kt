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
 * Represents a single knowledge node (neuron) in the CLU/BOX GraphRAG memory system.
 *
 * @param id       Unique identifier for this node (e.g. a UUID).
 * @param label    Human-readable name for the node.
 * @param type     Category or type of the node (e.g. "concept", "entity", "fact").
 * @param content  The raw text content stored at this node.
 */
@Entity(tableName = "neurons")
data class NeuronEntity(
  @PrimaryKey val id: String,
  val label: String,
  val type: String,
  val content: String,
)
