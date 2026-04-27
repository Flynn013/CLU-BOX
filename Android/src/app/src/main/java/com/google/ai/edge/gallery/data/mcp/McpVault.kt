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

package com.google.ai.edge.gallery.data.mcp

import android.content.Context
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.json.JSONArray
import org.json.JSONObject

private const val TAG = "McpVault"
private const val PREFS_FILE = "mcp_vault"
private const val KEY_SERVERS = "servers"

/**
 * Encrypted persistence layer for [McpServerConfig] objects.
 *
 * Credentials (environment variables) are stored via [EncryptedSharedPreferences] backed by an
 * AES-256-GCM master key in the Android Keystore.  Values are **never** logged in plaintext.
 *
 * All reads and writes are synchronous and should be performed off the main thread
 * (e.g. from a coroutine on [kotlinx.coroutines.Dispatchers.IO]).
 */
class McpVault(context: Context) {

  private val masterKey =
    MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()

  private val prefs =
    EncryptedSharedPreferences.create(
      context,
      PREFS_FILE,
      masterKey,
      EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
      EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

  // ── Public API ─────────────────────────────────────────────────────────────

  /** Loads and deserialises all persisted [McpServerConfig] entries. */
  fun loadAll(): List<McpServerConfig> =
    try {
      parseServerList(prefs.getString(KEY_SERVERS, "[]") ?: "[]")
    } catch (e: Exception) {
      Log.e(TAG, "loadAll failed — returning empty list", e)
      emptyList()
    }

  /**
   * Persists [config], replacing any existing entry with the same [McpServerConfig.name].
   *
   * Env-var values are encrypted at rest; the key names are visible in the ciphertext index
   * only by name (AES-SIV), not by value.
   */
  fun save(config: McpServerConfig) {
    val current = loadAll().toMutableList()
    current.removeAll { it.name == config.name }
    current.add(config)
    prefs.edit().putString(KEY_SERVERS, serializeServerList(current)).apply()
    Log.d(TAG, "Saved config for '${config.name}'")
  }

  /** Removes the persisted config identified by [name]. */
  fun delete(name: String) {
    val current = loadAll().toMutableList()
    if (current.removeAll { it.name == name }) {
      prefs.edit().putString(KEY_SERVERS, serializeServerList(current)).apply()
      Log.d(TAG, "Deleted config for '$name'")
    }
  }

  // ── Serialisation helpers ─────────────────────────────────────────────────

  private fun serializeServerList(configs: List<McpServerConfig>): String {
    val arr = JSONArray()
    for (c in configs) {
      val envObj = JSONObject()
      c.envVars.forEach { (k, v) -> envObj.put(k, v) }
      arr.put(
        JSONObject().apply {
          put("name", c.name)
          put("command", c.command)
          put("envVars", envObj)
        }
      )
    }
    return arr.toString()
  }

  private fun parseServerList(json: String): List<McpServerConfig> {
    val arr = JSONArray(json)
    return (0 until arr.length()).map { i ->
      val obj = arr.getJSONObject(i)
      val envObj = obj.optJSONObject("envVars") ?: JSONObject()
      McpServerConfig(
        name = obj.getString("name"),
        command = obj.getString("command"),
        envVars = envObj.keys().asSequence().associateWith { envObj.getString(it) },
      )
    }
  }
}
