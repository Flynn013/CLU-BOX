/*
 * Copyright 2026 Flynn013 / CLU-BOX
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

// Ported from Flynn013/SPL-NTR ClaudeAuthManager (Apache-2.0), simplified for CLU-BOX:
// removed CLI file writes, switched to ClaudeCredentialStore object pattern.

package com.google.ai.edge.gallery.runtime.cloudproviders

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.TimeUnit
import android.util.Base64 as AndroidBase64

/**
 * Manages the Anthropic / Claude OAuth 2.0 PKCE authentication flow on Android.
 *
 * Flow:
 *  1. Generate PKCE verifier + challenge.
 *  2. Open browser to the Anthropic consent screen.
 *  3. Anthropic redirects to `REDIRECT_URI` which displays an authorisation code.
 *     The user copies and pastes it into the dialog shown by [ClaudeConnectButton].
 *  4. [exchangeCodeForTokens] POST to the token endpoint (JSON body, not form-encoded).
 *  5. Credentials are persisted via [ClaudeCredentialStore].
 *
 * Uses Anthropic's public CLI client ID — no user-side OAuth setup is required.
 */
class ClaudeAuthManager(private val context: Context) {

    companion object {
        private const val TAG = "ClaudeAuthManager"

        private const val AUTH_ENDPOINT  = "https://claude.ai/oauth/authorize"
        private const val TOKEN_ENDPOINT = "https://console.anthropic.com/v1/oauth/token"

        // Anthropic public installed-app client (same as Claude CLI — not a secret).
        private const val CLIENT_ID      = "9d1c250a-e61b-44d9-88ed-5944d1962f5e"
        private const val REDIRECT_URI   = "https://console.anthropic.com/oauth/code/callback"

        // Scopes matching Claude CLI / console.anthropic.com
        private val OAUTH_SCOPES = listOf("org:create_api_key", "user:profile", "user:inference")

        // 10-minute window gives the user plenty of time to copy the code from the browser
        private const val OAUTH_TIMEOUT_MS = 600_000L
        private const val BASE64_URL_FLAGS =
            AndroidBase64.URL_SAFE or AndroidBase64.NO_WRAP or AndroidBase64.NO_PADDING
    }

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val authMutex = Mutex()

    @Volatile private var pendingCodeEntry: CompletableDeferred<String>? = null
    @Volatile private var activePkceVerifier: String? = null
    @Volatile private var activeState: String? = null

    // ── Public API ─────────────────────────────────────────────────────────────

    fun hasValidCredentials(): Boolean = ClaudeCredentialStore.hasValidCredentials(context)

    fun signOut() = ClaudeCredentialStore.clearAll(context)

    /** Called by the UI when the user pastes the authorisation code. */
    fun submitAuthorizationCode(code: String) {
        val trimmed = code.trim()
        if (trimmed.isNotEmpty()) pendingCodeEntry?.complete(trimmed)
    }

    /** Cancel the current OAuth flow. */
    fun cancelFlow() {
        pendingCodeEntry?.completeExceptionally(RuntimeException("Sign-in cancelled by user"))
        pendingCodeEntry = null
        activePkceVerifier = null
        activeState = null
    }

    /**
     * Start the OAuth PKCE flow.  Opens the browser, then suspends until the
     * user pastes the authorisation code via [submitAuthorizationCode].
     */
    suspend fun startOAuthFlow(): ClaudeCredentialStore.Credentials = authMutex.withLock {
        val pkce = generatePkce()
        activePkceVerifier = pkce.verifier

        val state = UUID.randomUUID().toString().replace("-", "")
        activeState = state

        val deferred = CompletableDeferred<String>()
        pendingCodeEntry = deferred

        val authUrl = buildAuthUrl(pkce, state)
        launchBrowser(authUrl)

        Log.i(TAG, "Browser opened for Anthropic OAuth consent")

        val rawCode: String? = try {
            withTimeoutOrNull(OAUTH_TIMEOUT_MS) { deferred.await() }
        } finally {
            pendingCodeEntry = null
            activePkceVerifier = null
            activeState = null
        }

        if (rawCode == null) throw RuntimeException("OAuth timed out — please try again.")

        // Anthropic may append the state after '#' in the code string shown to the user
        val parts = rawCode.split("#", limit = 2)
        val code = parts[0].trim()

        Log.i(TAG, "Received authorisation code, exchanging for tokens")

        val tokens = withContext(Dispatchers.IO) { exchangeCodeForTokens(code, pkce, state) }
        ClaudeCredentialStore.save(context, tokens)
        Log.i(TAG, "Anthropic credentials saved")
        tokens
    }

    /**
     * Attempt a silent token refresh using the stored refresh token.
     * Returns the refreshed credentials, or null if refresh fails or no credentials exist.
     */
    suspend fun refreshIfNeeded(): ClaudeCredentialStore.Credentials? {
        val stored = ClaudeCredentialStore.load(context) ?: return null
        if (!stored.isExpired) return stored

        return withContext(Dispatchers.IO) {
            try {
                val refreshed = refreshAccessToken(stored) ?: return@withContext null
                ClaudeCredentialStore.save(context, refreshed)
                Log.i(TAG, "Token refreshed successfully")
                refreshed
            } catch (e: Exception) {
                Log.w(TAG, "Token refresh failed: ${e.message}")
                null
            }
        }
    }

    /** Return a valid access token, refreshing silently if needed. */
    suspend fun getAccessToken(): String {
        val credentials = refreshIfNeeded()
            ?: ClaudeCredentialStore.load(context)
            ?: throw IllegalStateException(
                "Not authenticated with Anthropic. Go to SETTINGS → CLOUD and sign in."
            )
        return credentials.accessToken
    }

    // ── Internal helpers ───────────────────────────────────────────────────────

    private data class PkceChallenge(val verifier: String, val challenge: String)

    private fun generatePkce(): PkceChallenge {
        val bytes = ByteArray(64)
        SecureRandom().nextBytes(bytes)
        val verifier = AndroidBase64.encodeToString(bytes, BASE64_URL_FLAGS)
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(verifier.toByteArray(Charsets.US_ASCII))
        val challenge = AndroidBase64.encodeToString(digest, BASE64_URL_FLAGS)
        return PkceChallenge(verifier, challenge)
    }

    private fun buildAuthUrl(pkce: PkceChallenge, state: String): String {
        val params = mapOf(
            "response_type"         to "code",
            "client_id"             to CLIENT_ID,
            "redirect_uri"          to REDIRECT_URI,
            "scope"                 to OAUTH_SCOPES.joinToString(" "),
            "code_challenge"        to pkce.challenge,
            "code_challenge_method" to "S256",
            "state"                 to state,
            "code"                  to "true",
        )
        val query = params.entries.joinToString("&") { (k, v) ->
            "${Uri.encode(k)}=${Uri.encode(v)}"
        }
        return "$AUTH_ENDPOINT?$query"
    }

    private fun launchBrowser(url: String) {
        try {
            val customTabsIntent = androidx.browser.customtabs.CustomTabsIntent.Builder()
                .setShowTitle(true)
                .build()
            customTabsIntent.intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            customTabsIntent.launchUrl(context, Uri.parse(url))
        } catch (e: Exception) {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        }
    }

    private fun exchangeCodeForTokens(
        code: String,
        pkce: PkceChallenge,
        state: String,
    ): ClaudeCredentialStore.Credentials {
        val jsonBody = JSONObject().apply {
            put("grant_type",    "authorization_code")
            put("code",          code)
            put("redirect_uri",  REDIRECT_URI)
            put("client_id",     CLIENT_ID)
            put("code_verifier", pkce.verifier)
            if (state.isNotEmpty()) put("state", state)
        }.toString()

        val body = jsonBody.toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url(TOKEN_ENDPOINT)
            .post(body)
            .header("Content-Type", "application/json")
            .build()

        val response = httpClient.newCall(request).execute()
        val responseBody = response.body?.string()
            ?: throw RuntimeException("Empty token response from Anthropic")

        if (!response.isSuccessful) {
            Log.e(TAG, "Token exchange failed (${response.code}): $responseBody")
            throw RuntimeException("Token exchange failed (HTTP ${response.code}). Check the code and try again.")
        }

        val json = JSONObject(responseBody)
        return ClaudeCredentialStore.Credentials(
            accessToken  = json.getString("access_token"),
            refreshToken = json.optString("refresh_token", ""),
            expiresAtMs  = System.currentTimeMillis() + (json.optLong("expires_in", 3600) * 1000L),
        )
    }

    private fun refreshAccessToken(
        stored: ClaudeCredentialStore.Credentials,
    ): ClaudeCredentialStore.Credentials? {
        if (stored.refreshToken.isBlank()) return null

        val jsonBody = JSONObject().apply {
            put("grant_type",    "refresh_token")
            put("refresh_token", stored.refreshToken)
            put("client_id",     CLIENT_ID)
        }.toString()

        val body = jsonBody.toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url(TOKEN_ENDPOINT)
            .post(body)
            .header("Content-Type", "application/json")
            .build()

        return try {
            val response = httpClient.newCall(request).execute()
            val responseBody = response.body?.string() ?: return null
            if (!response.isSuccessful) {
                Log.w(TAG, "Token refresh failed (${response.code}): $responseBody")
                return null
            }
            val json = JSONObject(responseBody)
            stored.copy(
                accessToken  = json.getString("access_token"),
                refreshToken = json.optString("refresh_token", stored.refreshToken),
                expiresAtMs  = System.currentTimeMillis() + (json.optLong("expires_in", 3600) * 1000L),
            )
        } catch (e: Exception) {
            Log.w(TAG, "Token refresh exception: ${e.message}")
            null
        }
    }
}
