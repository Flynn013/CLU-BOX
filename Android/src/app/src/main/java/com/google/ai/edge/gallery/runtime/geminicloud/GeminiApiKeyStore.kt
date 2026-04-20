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

package com.google.ai.edge.gallery.runtime.geminicloud

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys

/**
 * Securely stores and retrieves the Gemini API key using Android
 * [EncryptedSharedPreferences]. The key is never held in plain-text
 * storage or committed to source control.
 */
object GeminiApiKeyStore {

  private const val PREFS_FILE = "gemini_cloud_secure_prefs"
  private const val KEY_API_KEY = "gemini_api_key"

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

  /** Returns the stored API key, or `null` if none has been saved yet. */
  fun getApiKey(context: Context): String? {
    return getPrefs(context).getString(KEY_API_KEY, null)?.takeIf { it.isNotBlank() }
  }

  /** Persists [apiKey] into encrypted storage. */
  fun setApiKey(context: Context, apiKey: String) {
    getPrefs(context).edit().putString(KEY_API_KEY, apiKey).apply()
  }

  /** Returns `true` when a non-blank API key is already stored. */
  fun hasApiKey(context: Context): Boolean = getApiKey(context) != null

  /** Removes the stored API key. */
  fun clearApiKey(context: Context) {
    getPrefs(context).edit().remove(KEY_API_KEY).apply()
  }
}
