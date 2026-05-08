/*
 * Copyright 2026 Flynn013 / CLU/BOX
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package com.google.ai.edge.gallery.goose.data

/**
 * All settings keys used by the Goose engine.
 *
 * Ported from MaxFlynn13/goose-android (data/SettingsStore.kt → SettingsKeys object).
 */
object SettingsKeys {
    // Provider API keys
    const val ANTHROPIC_API_KEY  = "goose_anthropic_api_key"
    const val OPENAI_API_KEY     = "goose_openai_api_key"
    const val GOOGLE_API_KEY     = "goose_google_api_key"
    const val GOOGLE_OAUTH_TOKEN = "goose_google_oauth_token"
    const val MISTRAL_API_KEY    = "goose_mistral_api_key"
    const val OPENROUTER_API_KEY = "goose_openrouter_api_key"
    const val DATABRICKS_API_KEY = "goose_databricks_api_key"

    // Custom / Ollama provider
    const val CUSTOM_PROVIDER_URL   = "goose_custom_provider_url"
    const val CUSTOM_PROVIDER_KEY   = "goose_custom_provider_key"
    const val CUSTOM_PROVIDER_MODEL = "goose_custom_provider_model"
    const val OLLAMA_BASE_URL       = "goose_ollama_base_url"

    // Active provider/model selection
    const val ACTIVE_PROVIDER = "goose_active_provider"
    const val ACTIVE_MODEL    = "goose_active_model"

    // Local (on-device) model selection
    const val LOCAL_MODEL_ID  = "goose_local_model_id"

    // GitHub integration
    const val GITHUB_TOKEN = "goose_github_token"

    // Working directory
    const val WORKING_DIRECTORY = "goose_working_directory"
    const val SHELL_PATH        = "goose_shell_path"

    // Extensions
    const val EXTENSION_DEVELOPER = "goose_ext_developer"
    const val EXTENSION_MEMORY    = "goose_ext_memory"
}
