/*
 * Copyright 2026 Flynn013 / CLU-BOX
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package com.google.ai.edge.gallery.runtime.cloudproviders

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys

/**
 * Encrypted on-device store for Anthropic Claude OAuth credentials.
 *
 * Uses [EncryptedSharedPreferences] backed by the Android Keystore so tokens
 * never touch disk in plain-text.  Mirrors the [GeminiTokenManager] singleton
 * pattern so call sites can use it without holding an instance.
 */
object ClaudeCredentialStore {

    private const val TAG = "ClaudeCredentialStore"

    private const val PREFS_FILE       = "claude_credentials"
    private const val KEY_ACCESS_TOKEN = "access_token"
    private const val KEY_REFRESH_TOKEN = "refresh_token"
    private const val KEY_EXPIRES_AT_MS = "expires_at_ms"

    private const val REFRESH_BUFFER_MS = 60_000L

    @Volatile
    private var prefs: SharedPreferences? = null

    // ── Credentials data class ────────────────────────────────────────────────

    data class Credentials(
        val accessToken: String,
        val refreshToken: String,
        val expiresAtMs: Long,
    ) {
        val isExpired: Boolean
            get() = System.currentTimeMillis() > (expiresAtMs - REFRESH_BUFFER_MS)
    }

    // ── Lazy prefs init ────────────────────────────────────────────────────────

    private fun getPrefs(context: Context): SharedPreferences =
        prefs ?: synchronized(this) {
            prefs ?: EncryptedSharedPreferences.create(
                PREFS_FILE,
                MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC),
                context.applicationContext,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            ).also { prefs = it }
        }

    // ── Public API ─────────────────────────────────────────────────────────────

    /** Return stored credentials, or null if none are present. */
    fun load(context: Context): Credentials? {
        val p = getPrefs(context)
        val access = p.getString(KEY_ACCESS_TOKEN, null) ?: return null
        val refresh = p.getString(KEY_REFRESH_TOKEN, null) ?: ""
        val expiresAtMs = p.getLong(KEY_EXPIRES_AT_MS, 0L)
        return Credentials(access, refresh, expiresAtMs)
    }

    /** Persist new credentials atomically. */
    fun save(context: Context, credentials: Credentials) {
        getPrefs(context).edit()
            .putString(KEY_ACCESS_TOKEN, credentials.accessToken)
            .putString(KEY_REFRESH_TOKEN, credentials.refreshToken)
            .putLong(KEY_EXPIRES_AT_MS, credentials.expiresAtMs)
            .apply()
        Log.i(TAG, "Credentials saved (expires in ${(credentials.expiresAtMs - System.currentTimeMillis()) / 1000}s)")
    }

    /** Return true if non-expired credentials are currently stored. */
    fun hasValidCredentials(context: Context): Boolean =
        load(context)?.isExpired == false

    /** Erase all stored credentials. */
    fun clearAll(context: Context) {
        getPrefs(context).edit().clear().apply()
        Log.i(TAG, "Credentials cleared")
    }
}
