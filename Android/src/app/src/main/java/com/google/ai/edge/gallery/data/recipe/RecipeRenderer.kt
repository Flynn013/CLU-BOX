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

import android.util.Log

private const val TAG = "RecipeRenderer"

/**
 * Renders a [Recipe] into the concrete strings needed to run it as an agent session.
 *
 * ## What "rendering" means
 * 1. **Parameter interpolation** — replace `{{paramName}}` tokens in [Recipe.instructions]
 *    and [Recipe.prompt] with the caller-supplied [params] map.
 * 2. **Missing required param detection** — returns an error description if any
 *    [RecipeParameter.required] parameter is absent from [params] (and has no [RecipeParameter.default]).
 * 3. **Default injection** — inserts [RecipeParameter.default] values for optional params
 *    not present in [params].
 *
 * @param recipe The [Recipe] to render
 * @param params Caller-supplied parameter overrides (from deep-link or UI)
 */
class RecipeRenderer(private val recipe: Recipe) {

    /**
     * The result of rendering a recipe.
     *
     * On success, [systemPrompt] and [seedPrompt] are ready to pass directly to the agent loop.
     */
    sealed class RenderResult {
        data class Success(
            /** System prompt (instructions with params interpolated). */
            val systemPrompt: String,
            /** Optional seed user message (prompt with params interpolated, or empty). */
            val seedPrompt: String,
            /** The effective provider id (from [RecipeSettings], or empty to use session default). */
            val providerId: String,
            /** The effective model id (from [RecipeSettings], or empty to use session default). */
            val modelId: String,
            /** Resolved max-iteration override, or 0 to use engine default. */
            val maxIterations: Int,
        ) : RenderResult()

        data class Error(val message: String) : RenderResult()
    }

    /**
     * Renders the recipe with the given [params].
     *
     * @param params Map of parameter name → value (may be empty)
     */
    fun render(params: Map<String, String> = emptyMap()): RenderResult {
        // Build effective params: user-supplied values override recipe defaults
        val effective = mutableMapOf<String, String>()
        for (p in recipe.parameters) {
            val value = params[p.name]
            when {
                !value.isNullOrBlank() -> effective[p.name] = value
                p.default.isNotBlank() -> effective[p.name] = p.default
                p.required -> {
                    val msg = "Missing required parameter '${p.name}': ${p.description}"
                    Log.w(TAG, "Recipe '${recipe.name}' render error: $msg")
                    return RenderResult.Error(msg)
                }
            }
        }

        // Warn about unknown params (not declared in recipe.parameters)
        val declaredNames = recipe.parameters.map { it.name }.toSet()
        for (key in params.keys) {
            if (key !in declaredNames) {
                Log.w(TAG, "Recipe '${recipe.name}': unknown param '$key' (ignored)")
            }
        }

        val systemPrompt = interpolate(recipe.instructions, effective)
        val seedPrompt = interpolate(recipe.prompt, effective)

        Log.d(
            TAG,
            "Rendered recipe '${recipe.name}' " +
                "(systemPrompt=${systemPrompt.length} chars, seedPrompt=${seedPrompt.length} chars)"
        )

        return RenderResult.Success(
            systemPrompt = systemPrompt,
            seedPrompt = seedPrompt,
            providerId = recipe.settings.providerId,
            modelId = recipe.settings.modelId,
            maxIterations = recipe.settings.maxIterations,
        )
    }

    /**
     * Returns the list of required parameters that are missing from [params]
     * (and have no default).
     *
     * Use this to drive a parameter-input UI before running the recipe.
     */
    fun missingRequired(params: Map<String, String>): List<RecipeParameter> =
        recipe.parameters.filter { p ->
            p.required && params[p.name].isNullOrBlank() && p.default.isBlank()
        }

    /** Returns `true` if [params] satisfies all [RecipeParameter.required] constraints. */
    fun isReadyToRun(params: Map<String, String>): Boolean =
        missingRequired(params).isEmpty()
}
