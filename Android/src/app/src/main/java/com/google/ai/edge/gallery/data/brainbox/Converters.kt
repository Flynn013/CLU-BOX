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

package com.google.ai.edge.gallery.data.brainbox

import androidx.room.TypeConverter

/**
 * Room [TypeConverter]s for the BrainBox vector database.
 *
 * Handles serialization of [FloatArray] embedding vectors to/from a
 * comma-separated [String] representation stored in SQLite TEXT columns.
 */
class Converters {

  /**
   * Serializes a [FloatArray] into a comma-separated string.
   * An empty array produces an empty string.
   */
  @TypeConverter
  fun fromFloatArray(value: FloatArray): String {
    return if (value.isEmpty()) "" else value.joinToString(",")
  }

  /**
   * Deserializes a comma-separated string back into a [FloatArray].
   * An empty or blank string produces an empty array.
   */
  @TypeConverter
  fun toFloatArray(value: String): FloatArray {
    if (value.isBlank()) return floatArrayOf()
    return try {
      value.split(",").map { it.toFloat() }.toFloatArray()
    } catch (_: NumberFormatException) {
      floatArrayOf()
    }
  }
}
