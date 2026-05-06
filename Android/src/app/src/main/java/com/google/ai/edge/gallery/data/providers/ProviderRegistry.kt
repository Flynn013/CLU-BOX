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

package com.google.ai.edge.gallery.data.providers

import android.content.Context
import android.util.Log
import com.google.ai.edge.gallery.runtime.geminicloud.GeminiApiKeyStore
import com.google.ai.edge.gallery.runtime.manualapi.ManualApiKeyStore

/**
 * Singleton factory and registry for all CLU/BOX [LlmProvider] implementations.
 *
 * Providers are instantiated on demand and cached by [providerId].  The registry
 * reads API keys from the existing encrypted key stores ([GeminiApiKeyStore] and
 * [ManualApiKeyStore]) so no additional secrets infrastructure is needed.
 *
 * ## Supported provider IDs
 * - `"gemini"` — Google Gemini cloud API ([GeminiProvider])
 * - `"anthropic"` — Anthropic Claude API ([AnthropicProvider])
 * - `"openai"` — OpenAI chat-completions or any compatible endpoint ([OpenAIProvider])
 * - `"litert"` — On-device LiteRT-LM inference ([LiteRTProvider]); must be pre-registered
 *                with [registerLiteRTProvider] after the model is loaded.
 *
 * ## Usage
 * ```kotlin
 * // Register on-device provider once the model is ready:
 * ProviderRegistry.registerLiteRTProvider(adapter, skillRegistry, modelId)
 *
 * // Create a cloud provider from stored keys:
 * val provider: LlmProvider? = ProviderRegistry.get("gemini", context, modelId = "gemini-2.0-flash")
 * ```
 */
object ProviderRegistry {

    private const val TAG = "ProviderRegistry"

    /** Cached provider instances keyed by "${providerId}:${modelId}". */
    private val cache = mutableMapOf<String, LlmProvider>()

    // ── LiteRT pre-registration ────────────────────────────────────────────

    /**
     * Pre-registers the on-device [LiteRTProvider].
     *
     * Must be called once by the agent chat screen after the LiteRT model finishes loading,
     * passing in the [ContinuousAgentDriver.InferenceAdapter] that was wired for the session.
     */
    fun registerLiteRTProvider(
        adapter: com.google.ai.edge.gallery.customtasks.agentchat.ContinuousAgentDriver.InferenceAdapter,
        skillRegistry: com.google.ai.edge.gallery.customtasks.agentchat.SkillRegistry,
        modelId: String,
    ) {
        val key = cacheKey("litert", modelId)
        cache[key] = LiteRTProvider(adapter, skillRegistry, modelId)
        Log.d(TAG, "LiteRTProvider registered for modelId=$modelId")
    }

    // ── Provider retrieval ─────────────────────────────────────────────────

    /**
     * Returns a [LlmProvider] for the given [providerId] and [modelId], or `null` if the
     * required API key is missing or the provider type is unknown.
     *
     * For cloud providers the key is read from [GeminiApiKeyStore] / [ManualApiKeyStore].
     * Pass [baseUrl] to override the default endpoint (useful for local servers).
     */
    fun get(
        providerId: String,
        context: Context,
        modelId: String,
        baseUrl: String? = null,
    ): LlmProvider? {
        val key = cacheKey(providerId, modelId)
        cache[key]?.let { return it }

        val provider: LlmProvider? = when (providerId) {
            "gemini" -> {
                val apiKey = GeminiApiKeyStore.getApiKey(context)
                if (apiKey.isNullOrBlank()) {
                    Log.w(TAG, "No Gemini API key stored — cannot create GeminiProvider")
                    null
                } else {
                    GeminiProvider(apiKey, modelId)
                }
            }

            "anthropic" -> {
                val apiKey = ManualApiKeyStore.getApiKey(context, "anthropic")
                if (apiKey.isNullOrBlank()) {
                    Log.w(TAG, "No Anthropic API key stored — cannot create AnthropicProvider")
                    null
                } else {
                    AnthropicProvider(apiKey, modelId)
                }
            }

            "openai" -> {
                val apiKey = ManualApiKeyStore.getApiKey(context, "openai")
                if (apiKey.isNullOrBlank()) {
                    Log.w(TAG, "No OpenAI API key configured; proceeding without key (may fail if endpoint requires auth)")
                }
                val url = baseUrl
                    ?: ManualApiKeyStore.getApiKey(context, "openai_base_url")
                    ?: "https://api.openai.com/v1"
                OpenAIProvider(apiKey ?: "", modelId, url)
            }

            "litert" -> {
                // Must be pre-registered via registerLiteRTProvider()
                Log.w(TAG, "LiteRTProvider not yet registered — call registerLiteRTProvider() first")
                null
            }

            else -> {
                Log.e(TAG, "Unknown providerId: $providerId")
                null
            }
        }

        if (provider != null) {
            cache[key] = provider
            Log.d(TAG, "Created and cached $providerId provider for modelId=$modelId")
        }
        return provider
    }

    /**
     * Returns all currently registered provider IDs (including cached ones).
     * Useful for the Extensions / Provider screen to enumerate what is available.
     */
    fun registeredProviderIds(): List<String> =
        cache.keys.map { it.substringBefore(":") }.distinct()

    /**
     * Clears the cached provider for [providerId] + [modelId] (e.g. after an API key change).
     */
    fun invalidate(providerId: String, modelId: String) {
        val removed = cache.remove(cacheKey(providerId, modelId))
        if (removed != null) Log.d(TAG, "Invalidated $providerId:$modelId from cache")
    }

    /** Clears all cached providers. */
    fun invalidateAll() {
        cache.clear()
        Log.d(TAG, "All cached providers invalidated")
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    private fun cacheKey(providerId: String, modelId: String) = "$providerId:$modelId"
}
