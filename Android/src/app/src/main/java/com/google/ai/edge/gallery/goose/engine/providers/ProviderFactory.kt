/*
 * Copyright 2026 Flynn013 / CLU/BOX
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package com.google.ai.edge.gallery.goose.engine.providers

/**
 * Factory for creating LLM provider instances.
 *
 * Ported from MaxFlynn13/goose-android (engine/providers/ProviderFactory.kt).
 */
object ProviderFactory {

    /**
     * Create an LLM provider by ID.
     *
     * @param providerId One of: "anthropic", "openai", "google"
     * @param apiKey     The API key for authentication
     * @param modelId    The model identifier
     * @param baseUrl    Optional base URL override (OpenAI-compatible providers)
     */
    fun create(
        providerId: String,
        apiKey: String,
        modelId: String,
        baseUrl: String? = null
    ): LlmProvider = when (providerId) {
        "anthropic" -> AnthropicProvider(apiKey, modelId)
        "openai" -> {
            if (baseUrl != null) OpenAIProvider(apiKey, modelId, baseUrl)
            else OpenAIProvider(apiKey, modelId)
        }
        "google" -> GoogleProvider(apiKey, modelId)
        else -> throw IllegalArgumentException(
            "Unknown provider: $providerId. Supported: anthropic, openai, google"
        )
    }

    /** List all supported provider IDs. */
    fun supportedProviders(): List<String> = listOf("anthropic", "openai", "google")
}
