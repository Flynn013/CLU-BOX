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
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/** Data Access Object for the CLU/BOX BrainBox knowledge graph. */
@Dao
interface BrainBoxDao {

  /** Inserts a neuron into the graph. Replaces on conflict. */
  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun insertNeuron(neuron: NeuronEntity)

  /** Returns all neurons stored in the knowledge graph. */
  @Query("SELECT * FROM neurons")
  suspend fun getAllNeurons(): List<NeuronEntity>

  /** Deletes the given neuron from the knowledge graph. */
  @Delete
  suspend fun deleteNeuron(neuron: NeuronEntity)
}
