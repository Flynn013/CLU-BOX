/*
 * Copyright 2026 Flynn013 / CLU/BOX
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

package com.google.ai.edge.gallery.data.recipe

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Persists and retrieves [Recipe]s as JSON files in `<filesDir>/cln_box/`,
 * co-located with CLN_BOX persona files.
 *
 * Recipe files use the naming convention `<recipeName>.recipe.json` so they
 * are distinguishable from plain persona files (`<personaName>.json`).
 *
 * All I/O is performed on [Dispatchers.IO].
 */
class RecipeStore(context: Context) {

    companion object {
        private const val TAG = "RecipeStore"
        private const val RECIPE_SUFFIX = ".recipe.json"
    }

    private val recipeDir: File =
        File(context.applicationContext.filesDir, "cln_box").apply { mkdirs() }

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    // ── CRUD ────────────────────────────────────────────────────────────────

    /**
     * Saves [recipe] to disk.
     *
     * Uses atomic write (write to `.tmp`, then rename) to protect against
     * corruption on process death.
     */
    suspend fun save(recipe: Recipe): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val file = fileFor(recipe.name)
            val tmp = File(recipeDir, "${recipe.name}.recipe.tmp")
            tmp.writeText(json.encodeToString(Recipe.serializer(), recipe))
            tmp.renameTo(file)
            Log.d(TAG, "Saved recipe '${recipe.name}' → ${file.path}")
        }
    }

    /**
     * Loads the recipe with the given [name], or `null` if not found.
     */
    suspend fun load(name: String): Recipe? = withContext(Dispatchers.IO) {
        val file = fileFor(name)
        if (!file.exists()) return@withContext null
        runCatching {
            json.decodeFromString(Recipe.serializer(), file.readText())
        }.onFailure { e ->
            Log.e(TAG, "Failed to parse recipe '$name': ${e.message}")
        }.getOrNull()
    }

    /**
     * Returns all saved recipes, sorted by [Recipe.title].
     */
    suspend fun listAll(): List<Recipe> = withContext(Dispatchers.IO) {
        recipeDir
            .listFiles { f -> f.isFile && f.name.endsWith(RECIPE_SUFFIX) }
            ?.sortedBy { it.nameWithoutExtension }
            ?.mapNotNull { f ->
                runCatching {
                    json.decodeFromString(Recipe.serializer(), f.readText())
                }.onFailure { e ->
                    Log.e(TAG, "Skip malformed recipe '${f.name}': ${e.message}")
                }.getOrNull()
            }
            ?: emptyList()
    }

    /**
     * Deletes the recipe with [name].
     *
     * @return `true` if the file was deleted, `false` if it did not exist.
     */
    suspend fun delete(name: String): Boolean = withContext(Dispatchers.IO) {
        val deleted = fileFor(name).delete()
        Log.d(TAG, "Delete recipe '$name': $deleted")
        deleted
    }

    /**
     * Returns `true` if a recipe with [name] exists on disk.
     */
    suspend fun exists(name: String): Boolean = withContext(Dispatchers.IO) {
        fileFor(name).exists()
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private fun fileFor(name: String): File =
        File(recipeDir, "$name$RECIPE_SUFFIX")
}
