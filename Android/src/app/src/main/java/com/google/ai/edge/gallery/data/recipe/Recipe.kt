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

// Recipe schema adapted from the Goose recipe/mod.rs data model.
// CLU/BOX storage: recipes are saved as JSON files in the cln_box directory alongside
// persona files, making them editable from the file manager and browsable from the
// existing CLN_BOX UI.

package com.google.ai.edge.gallery.data.recipe

import kotlinx.serialization.Serializable

// ── Data model ─────────────────────────────────────────────────────────────────

/**
 * The author block of a [Recipe].
 *
 * Maps to Goose's `Author { contact, metadata }`.
 */
@Serializable
data class RecipeAuthor(
    val contact: String = "",
    val metadata: String = "",
)

/**
 * Model / provider configuration overrides embedded in a [Recipe].
 *
 * When a recipe specifies a [modelId] or [providerId], the agent loop switches
 * to that provider/model for the duration of the recipe.
 */
@Serializable
data class RecipeSettings(
    /** Override provider id, e.g. "gemini", "anthropic", "openai", "litert". */
    val providerId: String = "",
    /** Override model id within the provider. */
    val modelId: String = "",
    /** Maximum number of agent-loop iterations for this recipe. */
    val maxIterations: Int = 0,
    /** Extra system-prompt text appended before executing the recipe instructions. */
    val systemPromptExtension: String = "",
)

/**
 * A parameter the recipe accepts as a fill-in-the-blank token.
 *
 * Maps to Goose's `Parameter { name, description, required, default }`.
 */
@Serializable
data class RecipeParameter(
    val name: String,
    val description: String = "",
    val required: Boolean = false,
    val default: String = "",
)

/**
 * A sub-recipe reference — a recipe that is spawned by the parent via [DelegationEngine].
 *
 * Maps to Goose's `SubRecipe { name, description }`.
 */
@Serializable
data class SubRecipeRef(
    /** Name of the sub-recipe (must exist in [RecipeStore]). */
    val name: String,
    val description: String = "",
)

/**
 * A CLU/BOX Recipe.
 *
 * Recipes are the CLU/BOX equivalent of Goose recipes — reusable, shareable, parameterisable
 * agent programs stored as JSON files.  Recipes extend CLN_BOX personas: a recipe *is* a persona
 * plus parameters, sub-recipes, extension allow-lists, and an optional response schema.
 *
 * ## Storage
 * Saved as `<recipeName>.recipe.json` in the `cln_box/` directory alongside persona files.
 *
 * ## Deep-link
 * Recipes can be shared via `clu://recipe?name=<name>&params=<urlencoded-json>` deep-links,
 * handled by [RecipeDeeplink].
 *
 * @param name            Unique identifier / display name (slug-like, no spaces)
 * @param title           Human-readable title shown in the UI
 * @param description     Short description shown in the recipe library
 * @param instructions    The full system prompt / instructions for the recipe
 * @param prompt          An optional default user prompt to seed the session
 * @param author          Author contact info
 * @param settings        Model/provider overrides
 * @param parameters      Declared parameters (filled by the user or deep-link)
 * @param extensions      Extension / skill allow-list (empty = use session default)
 * @param subRecipes      Sub-recipes this recipe may delegate to
 * @param version         Semver string, e.g. "1.0.0"
 */
@Serializable
data class Recipe(
    val name: String,
    val title: String = name,
    val description: String = "",
    val instructions: String = "",
    val prompt: String = "",
    val author: RecipeAuthor = RecipeAuthor(),
    val settings: RecipeSettings = RecipeSettings(),
    val parameters: List<RecipeParameter> = emptyList(),
    val extensions: List<String> = emptyList(),
    val subRecipes: List<SubRecipeRef> = emptyList(),
    val version: String = "1.0.0",
)

// ── Parameter interpolation ────────────────────────────────────────────────────

/**
 * Returns a copy of [template] with `{{paramName}}` tokens replaced by the
 * corresponding values in [params].
 *
 * Unmatched tokens are left as-is so the model sees the raw placeholder name.
 */
fun interpolate(template: String, params: Map<String, String>): String {
    var result = template
    for ((key, value) in params) {
        result = result.replace("{{$key}}", value)
    }
    return result
}
