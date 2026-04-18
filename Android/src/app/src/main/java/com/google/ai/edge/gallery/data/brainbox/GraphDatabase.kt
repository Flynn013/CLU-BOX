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

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * The Room database for the CLU/BOX BrainBox GraphRAG memory system.
 *
 * Increment [version] whenever the schema changes and provide a migration strategy.
 */
@Database(
  entities = [NeuronEntity::class, ChatMessageEntity::class],
  version = 4,
  exportSchema = false,
)
abstract class GraphDatabase : RoomDatabase() {

  abstract fun brainBoxDao(): BrainBoxDao

  abstract fun chatHistoryDao(): ChatHistoryDao

  companion object {
    @Volatile private var INSTANCE: GraphDatabase? = null

    /** Migration from v2 → v3: add the synapses column to the neurons table. */
    private val MIGRATION_2_3 =
      object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
          db.execSQL("ALTER TABLE neurons ADD COLUMN synapses TEXT NOT NULL DEFAULT ''")
        }
      }

    /** Migration from v3 → v4: add the isCore column to the neurons table. */
    private val MIGRATION_3_4 =
      object : Migration(3, 4) {
        override fun migrate(db: SupportSQLiteDatabase) {
          db.execSQL("ALTER TABLE neurons ADD COLUMN isCore INTEGER NOT NULL DEFAULT 0")
        }
      }

    fun getInstance(context: Context): GraphDatabase {
      return INSTANCE
        ?: synchronized(this) {
          Room.databaseBuilder(
              context.applicationContext,
              GraphDatabase::class.java,
              "brainbox.db",
            )
            .addMigrations(MIGRATION_2_3, MIGRATION_3_4)
            .fallbackToDestructiveMigration() // TODO: replace with proper migrations before release
            .build()
            .also { INSTANCE = it }
        }
    }
  }
}
