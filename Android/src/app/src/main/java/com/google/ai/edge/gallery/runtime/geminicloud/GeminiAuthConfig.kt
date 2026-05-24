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

import androidx.core.net.toUri
import net.openid.appauth.AuthorizationServiceConfiguration

/**
 * OAuth 2.0 constants for "Gemini Proper" access via a consumer Google account.
 *
 * CLIENT_ID must be an OAuth 2.0 client ID created in the Google Cloud Console
 * with application type "Android" (or "Web application" if you use the loopback
 * redirect trick). Replace the placeholder before shipping.
 *
 * The redirect URI scheme **must** match the `manifestPlaceholders["appAuthRedirectScheme"]`
 * value in `build.gradle.kts` AND the Authorized Redirect URI in the Cloud Console.
 */
object GeminiAuthConfig {

    // ── Google Cloud Console OAuth 2.0 Client ID ─────────────────
    @Volatile
    var CLIENT_ID: String = "REPLACE_WITH_GOOGLE_OAUTH_CLIENT_ID"

    // ── Redirect URI — must be registered in Cloud Console ──────────────────
    const val REDIRECT_URI: String = "clubox://oauth2callback"

    // ── Scopes ───────────────────────────────────────────────────────────────
    const val SCOPE_GENERATIVE: String =
        "https://www.googleapis.com/auth/generative-language"
    const val SCOPE_OPENID:  String = "openid"
    const val SCOPE_EMAIL:   String = "email"
    const val SCOPE_PROFILE: String = "profile"

    /** Space-delimited scope string accepted by AppAuth's `setScope()`. */
    val scopes: String = listOf(
        SCOPE_GENERATIVE,
        SCOPE_OPENID,
        SCOPE_EMAIL,
        SCOPE_PROFILE,
    ).joinToString(" ")

    // ── Google's well-known OIDC endpoints ───────────────────────────────────
    private const val AUTH_ENDPOINT  =
        "https://accounts.google.com/o/oauth2/v2/auth"
    private const val TOKEN_ENDPOINT =
        "https://oauth2.googleapis.com/token"

    /**
     * AppAuth [AuthorizationServiceConfiguration] pointing at Google's OAuth endpoints.
     * Built once; safe to share across the process lifetime.
     */
    val serviceConfig: AuthorizationServiceConfiguration =
        AuthorizationServiceConfiguration(
            AUTH_ENDPOINT.toUri(),
            TOKEN_ENDPOINT.toUri(),
        )
}
