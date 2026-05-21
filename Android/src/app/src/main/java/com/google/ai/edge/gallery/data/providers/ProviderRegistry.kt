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
import com.google.ai.edge.gallery.runtime.cloudproviders.ClaudeAuthManager
import com.google.ai.edge.gallery.runtime.cloudproviders.ClaudeCredentialStore
import com.google.ai.edge.gallery.runtime.geminicloud.GeminiApiKeyStore
import com.google.ai.edge.gallery.runtime.geminicloud.GeminiAuthManager
import com.google.ai.edge.gallery.runtime.geminicloud.GeminiTokenManager
import com.google.ai.edge.gallery.runtime.manualapi.ManualApiKeyStore
import kotlinx.coroutines.runBlocking

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

        // For Anthropic OAuth providers, validate cached credentials before returning.
        // If OAuth credentials expired since the provider was created, bust the cache
        // so the next call creates a fresh provider with a valid token.
        if (providerId == "anthropic") {
            val cached = cache[key]
            if (cached != null) {
                val oauthCreds = ClaudeCredentialStore.load(context)
                if (oauthCreds != null && !oauthCreds.isExpired) {
                    return cached // Valid OAuth token — reuse cached provider
                }
                // Token expired or revoked — bust the cache and fall through to recreate
                cache.remove(key)
                Log.d(TAG, "Busted stale Anthropic OAuth provider from cache")
            }
        } else {
            cache[key]?.let { return it }
        }

        val provider: LlmProvider? = when (providerId) {
            "gemini" -> {
                // OAuth bearer takes precedence over API key.
                val hasOAuth = GeminiTokenManager.hasValidAccessToken(context)
                    || GeminiTokenManager.getRefreshToken(context) != null
                if (hasOAuth) {
                    val token = runCatching {
                        runBlocking { GeminiAuthManager(context).getValidAccessToken() }
                    }.getOrNull()
                    if (token != null) {
                        Log.d(TAG, "Creating GeminiProvider with OAuth bearer token")
                        GeminiProvider(apiKey = "", modelId = modelId, bearerToken = token)
                    } else {
                        Log.w(TAG, "Gemini OAuth token retrieval failed, falling back to API key")
                        val apiKey = GeminiApiKeyStore.getApiKey(context)
                        if (apiKey.isNullOrBlank()) { Log.w(TAG, "No Gemini API key stored"); null }
                        else GeminiProvider(apiKey, modelId)
                    }
                } else {
                    val apiKey = GeminiApiKeyStore.getApiKey(context)
                    if (apiKey.isNullOrBlank()) {
                        Log.w(TAG, "No Gemini auth configured — cannot create GeminiProvider")
                        null
                    } else {
                        GeminiProvider(apiKey, modelId)
                    }
                }
            }

            "anthropic" -> {
                // OAuth bearer takes precedence over API key.
                val oauthCreds = ClaudeCredentialStore.load(context)
                when {
                    oauthCreds != null && !oauthCreds.isExpired -> {
                        Log.d(TAG, "Creating AnthropicProvider with OAuth bearer token")
                        AnthropicProvider(apiKey = "", modelId = modelId, bearerToken = oauthCreds.accessToken)
                    }
                    oauthCreds != null && oauthCreds.isExpired -> {
                        val refreshed = runCatching {
                            runBlocking { ClaudeAuthManager(context).refreshIfNeeded() }
                        }.getOrNull()
                        if (refreshed != null) {
                            Log.d(TAG, "Creating AnthropicProvider with refreshed OAuth token")
                            AnthropicProvider(apiKey = "", modelId = modelId, bearerToken = refreshed.accessToken)
                        } else {
                            Log.w(TAG, "Claude token refresh failed, falling back to API key")
                            fallbackToAnthropicApiKey(context, modelId)
                        }
                    }
                    else -> fallbackToAnthropicApiKey(context, modelId)
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

    private fun fallbackToAnthropicApiKey(context: Context, modelId: String): LlmProvider? {
        val apiKey = ManualApiKeyStore.getApiKey(context, "anthropic")
        return if (apiKey.isNullOrBlank()) {
            Log.w(TAG, "No Anthropic auth configured — cannot create AnthropicProvider")
            null
        } else {
            AnthropicProvider(apiKey, modelId)
        }
    }

    private fun cacheKey(providerId: String, modelId: String) = "$providerId:$modelId"
}
