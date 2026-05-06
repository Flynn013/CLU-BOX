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

// Deep-link encoder/decoder for CLU/BOX recipes, mirroring the Goose recipe_deeplink.rs design.
// Scheme: clu://recipe?name=<name>&params=<urlencoded-JSON>

package com.google.ai.edge.gallery.data.recipe

import android.net.Uri
import android.util.Log
import org.json.JSONObject

private const val TAG = "RecipeDeeplink"

/** Scheme + host for CLU/BOX recipe deep-links. */
private const val RECIPE_SCHEME = "clu"
private const val RECIPE_HOST   = "recipe"

/**
 * Encodes and decodes `clu://recipe` deep-links for sharing [Recipe]s.
 *
 * ## Link format
 * ```
 * clu://recipe?name=<recipeName>&params=<urlencoded-json-object>
 * ```
 *
 * - `name`   — the recipe name (matches [Recipe.name])
 * - `params` — optional URL-encoded JSON object of parameter overrides
 *              (`{"param1":"value1","param2":"value2"}`)
 *
 * ## Android integration
 * Add the following intent filter to the `<activity>` in AndroidManifest.xml:
 * ```xml
 * <intent-filter>
 *     <action android:name="android.intent.action.VIEW" />
 *     <category android:name="android.intent.category.DEFAULT" />
 *     <category android:name="android.intent.category.BROWSABLE" />
 *     <data android:scheme="clu" android:host="recipe" />
 * </intent-filter>
 * ```
 */
object RecipeDeeplink {

    /**
     * Parses a `clu://recipe` [Uri] into a (recipeName, params) pair.
     *
     * Returns `null` if the URI does not match the expected scheme/host or
     * the `name` parameter is missing.
     */
    fun parse(uri: Uri): DeeplinkPayload? {
        if (uri.scheme != RECIPE_SCHEME || uri.host != RECIPE_HOST) {
            Log.w(TAG, "Not a recipe deep-link: $uri")
            return null
        }
        val name = uri.getQueryParameter("name")?.trim()
        if (name.isNullOrBlank()) {
            Log.w(TAG, "Recipe deep-link missing 'name' parameter: $uri")
            return null
        }
        val paramsJson = uri.getQueryParameter("params") ?: "{}"
        val params: Map<String, String> = try {
            val obj = JSONObject(paramsJson)
            obj.keys().asSequence().associateWith { obj.optString(it) }
        } catch (e: Exception) {
            Log.w(TAG, "Could not parse params JSON '$paramsJson': ${e.message}")
            emptyMap()
        }
        Log.d(TAG, "Parsed deep-link: name='$name', params=$params")
        return DeeplinkPayload(name, params)
    }

    /**
     * Encodes [recipeName] and optional [params] into a shareable `clu://recipe` URI string.
     */
    fun encode(recipeName: String, params: Map<String, String> = emptyMap()): String {
        val builder = Uri.Builder()
            .scheme(RECIPE_SCHEME)
            .authority(RECIPE_HOST)
            .appendQueryParameter("name", recipeName)
        if (params.isNotEmpty()) {
            val obj = JSONObject(params.map { (k, v) -> k to v }.toMap())
            builder.appendQueryParameter("params", obj.toString())
        }
        return builder.build().toString()
    }
}

/**
 * Parsed payload from a `clu://recipe` deep-link.
 *
 * @param recipeName  The recipe to load from [RecipeStore]
 * @param params      Parameter values to apply before rendering instructions
 */
data class DeeplinkPayload(
    val recipeName: String,
    val params: Map<String, String> = emptyMap(),
)
