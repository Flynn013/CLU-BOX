/*
 * Copyright 2026 Flynn013 / CLU-BOX
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package com.google.ai.edge.gallery.runtime.geminicloud

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys

/**
 * Encrypted on-device store for the Google OAuth 2.0 token pair.
 *
 * Uses [EncryptedSharedPreferences] backed by the Android Keystore so
 * tokens never touch disk in plain-text. All operations are synchronous
 * but cheap (prefs are mmapped); call from a coroutine/IO thread if you
 * are sensitive to blocking.
 */
object GeminiTokenManager {

    private const val PREFS_FILE      = "gemini_oauth_token_store"
    private const val KEY_ACCESS      = "access_token"
    private const val KEY_REFRESH     = "refresh_token"
    private const val KEY_EXPIRY_MS   = "access_token_expiry_epoch_ms"

    @Volatile
    private var prefs: SharedPreferences? = null

    // ── Lazy init ─────────────────────────────────────────────────────────────

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

    // ── Writers ───────────────────────────────────────────────────────────────

    /**
     * Persist [accessToken], [refreshToken] and [expiryEpochMs] atomically.
     *
     * [expiryEpochMs] should be `System.currentTimeMillis() + (expires_in_seconds * 1000)`.
     * If the server does not return an expiry, pass `Long.MAX_VALUE` to treat the token
     * as perpetually valid (until an HTTP 401 forces a refresh).
     */
    fun saveTokens(
        context: Context,
        accessToken: String,
        refreshToken: String?,
        expiryEpochMs: Long,
    ) {
        getPrefs(context).edit().apply {
            putString(KEY_ACCESS, accessToken)
            if (refreshToken != null) putString(KEY_REFRESH, refreshToken)
            putLong(KEY_EXPIRY_MS, expiryEpochMs)
            apply()
        }
    }

    /** Overwrite only the access token and its expiry (e.g. after a silent refresh). */
    fun updateAccessToken(context: Context, accessToken: String, expiryEpochMs: Long) {
        getPrefs(context).edit()
            .putString(KEY_ACCESS, accessToken)
            .putLong(KEY_EXPIRY_MS, expiryEpochMs)
            .apply()
    }

    // ── Readers ───────────────────────────────────────────────────────────────

    /** Returns the stored access token, or `null` if none is saved. */
    fun getAccessToken(context: Context): String? =
        getPrefs(context).getString(KEY_ACCESS, null)?.takeIf { it.isNotBlank() }

    /** Returns the stored refresh token, or `null` if none is saved. */
    fun getRefreshToken(context: Context): String? =
        getPrefs(context).getString(KEY_REFRESH, null)?.takeIf { it.isNotBlank() }

    /**
     * Returns `true` when the stored access token has expired (or will expire
     * within [bufferMs] milliseconds). A buffer of 60 seconds is a safe default
     * to account for clock skew and network latency.
     */
    fun isAccessTokenExpired(context: Context, bufferMs: Long = 60_000L): Boolean {
        val expiry = getPrefs(context).getLong(KEY_EXPIRY_MS, 0L)
        return System.currentTimeMillis() >= expiry - bufferMs
    }

    /** Convenience: returns `true` only when a valid, non-expired access token is stored. */
    fun hasValidAccessToken(context: Context): Boolean =
        getAccessToken(context) != null && !isAccessTokenExpired(context)

    // ── Deletion ──────────────────────────────────────────────────────────────

    /** Wipe every stored credential (logout / revoke). */
    fun clearAll(context: Context) {
        getPrefs(context).edit().clear().apply()
    }
}
