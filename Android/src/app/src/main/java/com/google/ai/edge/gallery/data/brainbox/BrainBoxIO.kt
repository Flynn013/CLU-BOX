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

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * Serializes all [NeuronEntity] rows into a raw JSON string.
 *
 * Example output:
 * ```json
 * [{"id":"abc","label":"Kotlin","type":"concept","content":"..."}]
 * ```
 */
suspend fun exportBrain(dao: BrainBoxDao): String {
  val neurons = dao.getAllNeurons()
  return Gson().toJson(neurons)
}

/**
 * Parses [json] and **overwrites** the BrainBox database with the deserialized neurons.
 *
 * All existing neurons are deleted before the new ones are inserted, providing a clean restore.
 *
 * @throws com.google.gson.JsonParseException if [json] is malformed.
 */
suspend fun importBrain(dao: BrainBoxDao, json: String) {
  val type = object : TypeToken<List<NeuronEntity>>() {}.type
  val neurons: List<NeuronEntity> = Gson().fromJson(json, type)
  dao.deleteAllNeurons()
  neurons.forEach { dao.insertNeuron(it) }
}
