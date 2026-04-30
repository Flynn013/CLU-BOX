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
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.google.ai.edge.gallery.data.scdlbox.ScdlBoxDao
import com.google.ai.edge.gallery.data.scdlbox.ScdlBoxEntity

/**
 * The Room database for the CLU/BOX BrainBox GraphRAG memory system.
 *
 * Increment [version] whenever the schema changes and provide a migration strategy.
 */
@Database(
  entities = [NeuronEntity::class, ChatMessageEntity::class, ScdlBoxEntity::class],
  version = 6,
  exportSchema = false,
)
@TypeConverters(Converters::class)
abstract class GraphDatabase : RoomDatabase() {

  abstract fun brainBoxDao(): BrainBoxDao

  abstract fun chatHistoryDao(): ChatHistoryDao

  abstract fun scdlBoxDao(): ScdlBoxDao

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

    /** Migration from v4 → v5: add falsePaths and embedding columns for vector search. */
    private val MIGRATION_4_5 =
      object : Migration(4, 5) {
        override fun migrate(db: SupportSQLiteDatabase) {
          db.execSQL("ALTER TABLE neurons ADD COLUMN falsePaths TEXT NOT NULL DEFAULT ''")
          db.execSQL("ALTER TABLE neurons ADD COLUMN embedding TEXT NOT NULL DEFAULT ''")
        }
      }

    /** Migration from v5 → v6: create the SCDL_BOX scheduled tasks table. */
    private val MIGRATION_5_6 =
      object : Migration(5, 6) {
        override fun migrate(db: SupportSQLiteDatabase) {
          db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS scdl_tasks (
              id TEXT NOT NULL PRIMARY KEY,
              title TEXT NOT NULL,
              description TEXT NOT NULL DEFAULT '',
              payload TEXT NOT NULL,
              isShellCommand INTEGER NOT NULL,
              intervalMinutes INTEGER NOT NULL,
              isEnabled INTEGER NOT NULL DEFAULT 1
            )
            """.trimIndent()
          )
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
            .addMigrations(MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6)
            .fallbackToDestructiveMigration() // TODO: replace with proper migrations before release
            .build()
            .also { INSTANCE = it }
        }
    }
  }
}
