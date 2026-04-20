/*
 * Copyright 2026 Google LLC
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

package com.google.ai.edge.gallery.runtime.manualapi

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys

/**
 * Securely stores and retrieves per-model API keys for BESPOKE (manual API)
 * models using Android [EncryptedSharedPreferences].
 *
 * Each model is keyed by its normalised name so that multiple bespoke models
 * can coexist with independent credentials.
 */
object ManualApiKeyStore {

  private const val PREFS_FILE = "manual_api_secure_prefs"
  private const val KEY_PREFIX = "manual_api_key_"

  @Volatile
  private var prefs: SharedPreferences? = null

  private fun getPrefs(context: Context): SharedPreferences {
    return prefs ?: synchronized(this) {
      prefs ?: EncryptedSharedPreferences.create(
        PREFS_FILE,
        MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC),
        context.applicationContext,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
      ).also { prefs = it }
    }
  }

  /** Returns the stored API key for [modelName], or `null` if none saved. */
  fun getApiKey(context: Context, modelName: String): String? {
    return getPrefs(context).getString("$KEY_PREFIX$modelName", null)?.takeIf { it.isNotBlank() }
  }

  /** Persists [apiKey] for the given [modelName] into encrypted storage. */
  fun setApiKey(context: Context, modelName: String, apiKey: String) {
    getPrefs(context).edit().putString("$KEY_PREFIX$modelName", apiKey).apply()
  }

  /** Returns `true` when a non-blank API key is stored for [modelName]. */
  fun hasApiKey(context: Context, modelName: String): Boolean =
    getApiKey(context, modelName) != null

  /** Removes the stored API key for [modelName]. */
  fun clearApiKey(context: Context, modelName: String) {
    getPrefs(context).edit().remove("$KEY_PREFIX$modelName").apply()
  }
}
