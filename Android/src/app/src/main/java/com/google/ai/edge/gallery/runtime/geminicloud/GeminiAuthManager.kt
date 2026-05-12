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
import android.net.Uri
import net.openid.appauth.AuthorizationException
import net.openid.appauth.AuthorizationRequest
import net.openid.appauth.AuthorizationResponse
import net.openid.appauth.AuthorizationService
import net.openid.appauth.ClientSecretBasic
import net.openid.appauth.ResponseTypeValues
import net.openid.appauth.TokenRequest
import net.openid.appauth.TokenResponse
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Core OAuth 2.0 PKCE handshake logic for reaching "Gemini Proper" with a
 * consumer Google account.
 *
 * **Usage pattern:**
 * 1. Call [buildAuthorizationRequest] to get an [AuthorizationRequest].
 * 2. Launch the browser via `AuthorizationService.getAuthorizationRequestIntent(request)`.
 * 3. On return from the browser, pass the result intent to [handleAuthorizationResponse].
 * 4. Thereafter call [getValidAccessToken] whenever you need a fresh bearer token.
 *
 * The manager is intentionally stateless between calls; it reads/writes tokens
 * through [GeminiTokenManager] so the ViewModel just coordinates the flow.
 *
 * ### ClientSecret note
 * Google's token endpoint requires a `client_secret` even for public clients
 * (the mobile OAuth2 "installed application" type). Pass your secret via the
 * constructor. If you registered a *desktop*-type client in the Cloud Console
 * (the recommended approach for Android apps that need a secret), the secret
 * ships in `google-services.json` or a build-time constant — never hardcoded
 * in this class.
 */
class GeminiAuthManager(
    private val context: Context,
    private val clientSecret: String? = null,
) {

    // ── Authorization Request ────────────────────────────────────────────────

    /**
     * Build an [AuthorizationRequest] with PKCE (S256 code challenge).
     *
     * The [AuthorizationService] will open a Chrome Custom Tab (or the system
     * browser as fallback) pointed at Google's consent screen.
     */
    fun buildAuthorizationRequest(): AuthorizationRequest =
        AuthorizationRequest.Builder(
            GeminiAuthConfig.serviceConfig,
            GeminiAuthConfig.CLIENT_ID,
            ResponseTypeValues.CODE,
            Uri.parse(GeminiAuthConfig.REDIRECT_URI),
        )
            .setScope(GeminiAuthConfig.scopes)
            // AppAuth generates a cryptographically-random code verifier and the
            // corresponding S256 challenge automatically — no manual PKCE wiring needed.
            .build()

    // ── Code → Token Exchange ────────────────────────────────────────────────

    /**
     * Exchange the authorization [response] for an access + refresh token pair.
     *
     * Suspends until the token endpoint responds. Throws [AuthorizationException]
     * on any OAuth error, or [IllegalStateException] if the response was null
     * (e.g. the user cancelled or the browser returned a malformed result).
     *
     * Tokens are persisted into [GeminiTokenManager] on success.
     */
    suspend fun handleAuthorizationResponse(
        response: AuthorizationResponse?,
        exception: AuthorizationException?,
    ): TokenResponse {
        if (exception != null) throw exception
        checkNotNull(response) { "Authorization response is null — user may have cancelled." }

        val service = AuthorizationService(context)
        return try {
            val tokenResponse = performTokenRequest(service, response.createTokenExchangeRequest())
            persistTokenResponse(tokenResponse)
            tokenResponse
        } finally {
            service.dispose()
        }
    }

    // ── Silent Token Refresh ─────────────────────────────────────────────────

    /**
     * Returns a valid access token, refreshing silently if the stored one has
     * expired (or is about to expire within the 60 s grace window).
     *
     * Throws [IllegalStateException] when no refresh token is available and the
     * current access token is also expired — the caller should re-launch the
     * authorization flow in this case.
     */
    suspend fun getValidAccessToken(): String {
        val ctx = context.applicationContext

        // Fast path — token is still fresh.
        if (!GeminiTokenManager.isAccessTokenExpired(ctx)) {
            return checkNotNull(GeminiTokenManager.getAccessToken(ctx)) {
                "Unexpectedly null access token despite non-expired state."
            }
        }

        // Refresh path.
        val refreshToken = GeminiTokenManager.getRefreshToken(ctx)
            ?: error(
                "Access token expired and no refresh token is available. " +
                    "Re-launch the authorization flow."
            )

        val service = AuthorizationService(ctx)
        return try {
            val refreshRequest = buildRefreshRequest(refreshToken)
            val tokenResponse = performTokenRequest(service, refreshRequest)
            persistTokenResponse(tokenResponse)
            checkNotNull(tokenResponse.accessToken) {
                "Token endpoint returned a response with a null access token."
            }
        } finally {
            service.dispose()
        }
    }

    // ── Internal helpers ─────────────────────────────────────────────────────

    private fun buildRefreshRequest(refreshToken: String): TokenRequest =
        TokenRequest.Builder(
            GeminiAuthConfig.serviceConfig,
            GeminiAuthConfig.CLIENT_ID,
        )
            .setGrantType("refresh_token")
            .setRefreshToken(refreshToken)
            .build()

    /** Wraps the callback-based [AuthorizationService.performTokenRequest] as a coroutine. */
    private suspend fun performTokenRequest(
        service: AuthorizationService,
        request: TokenRequest,
    ): TokenResponse = suspendCancellableCoroutine { cont ->
        val clientAuth = clientSecret?.let { ClientSecretBasic(it) }
        val callback = AuthorizationService.TokenResponseCallback { response, ex ->
            when {
                ex != null   -> cont.resumeWithException(ex)
                response != null -> cont.resume(response)
                else         -> cont.resumeWithException(
                    IllegalStateException("Both TokenResponse and exception are null.")
                )
            }
        }
        if (clientAuth != null) {
            service.performTokenRequest(request, clientAuth, callback)
        } else {
            service.performTokenRequest(request, callback)
        }
    }

    /** Persist a [TokenResponse] into [GeminiTokenManager]. */
    private fun persistTokenResponse(response: TokenResponse) {
        val accessToken  = response.accessToken  ?: return
        val expiryEpochMs = response.accessTokenExpirationTime
            ?: (System.currentTimeMillis() + 3600_000L) // default 1-hour TTL

        GeminiTokenManager.saveTokens(
            context  = context.applicationContext,
            accessToken  = accessToken,
            refreshToken = response.refreshToken,
            expiryEpochMs = expiryEpochMs,
        )
    }
}
