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

import android.app.Activity
import android.content.Intent
import androidx.activity.result.ActivityResult
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import net.openid.appauth.AuthorizationException
import net.openid.appauth.AuthorizationResponse

// ── UI State ─────────────────────────────────────────────────────────────────

/** Sealed hierarchy representing every possible state of the Gemini OAuth flow. */
sealed interface GeminiAuthUiState {
    /** Initial state — no attempt has been made yet (or was explicitly cleared). */
    data object Idle : GeminiAuthUiState

    /** The authorization browser tab is open / awaiting the user. */
    data object AwaitingBrowser : GeminiAuthUiState

    /** The code→token exchange is in-flight. */
    data object ExchangingToken : GeminiAuthUiState

    /** OAuth handshake completed successfully. [email] may be null if the OIDC
     *  id_token was not decoded (requires additional parsing). */
    data class Authenticated(val email: String?) : GeminiAuthUiState

    /** A recoverable or unrecoverable error occurred. */
    data class Error(val message: String) : GeminiAuthUiState
}

// ── ViewModel ─────────────────────────────────────────────────────────────────

/**
 * Manages the Google OAuth 2.0 PKCE flow for "Gemini Proper" connectivity.
 *
 * **Typical call sequence from a Composable:**
 * ```kotlin
 * val launcher = rememberLauncherForActivityResult(
 *     contract = ActivityResultContracts.StartActivityForResult()
 * ) { result -> viewModel.handleActivityResult(result) }
 *
 * // On button click:
 * val intent = viewModel.buildAuthIntent(context)
 * launcher.launch(intent)
 * ```
 *
 * The ViewModel owns the [GeminiAuthManager] so that the Activity Result
 * callback can reach it even after configuration changes.
 */
@HiltViewModel
class GeminiAuthViewModel @Inject constructor(
    private val authManagerFactory: GeminiAuthManagerFactory,
) : ViewModel() {

    private val _uiState = MutableStateFlow<GeminiAuthUiState>(GeminiAuthUiState.Idle)
    val uiState: StateFlow<GeminiAuthUiState> = _uiState.asStateFlow()

    // The manager is created lazily so we can inject the Context from the factory.
    private val authManager: GeminiAuthManager by lazy { authManagerFactory.create() }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Build the [Intent] that launches the browser-based consent screen.
     * The caller should pass this to an [ActivityResultLauncher].
     */
    fun buildAuthIntent(): Intent {
        val request = authManager.buildAuthorizationRequest()
        _uiState.update { GeminiAuthUiState.AwaitingBrowser }
        // AuthorizationService is short-lived here; we only need it for the intent.
        val service = net.openid.appauth.AuthorizationService(
            authManagerFactory.applicationContext
        )
        return service.getAuthorizationRequestIntent(request).also { service.dispose() }
    }

    /**
     * Process the [ActivityResult] from the browser redirect.
     * Must be called from the Activity Result API callback.
     */
    fun handleActivityResult(result: ActivityResult) {
        if (result.resultCode == Activity.RESULT_CANCELED) {
            _uiState.update { GeminiAuthUiState.Error("Sign-in cancelled.") }
            return
        }

        val intent = result.data
        if (intent == null) {
            _uiState.update { GeminiAuthUiState.Error("No data returned from browser.") }
            return
        }

        val response  = AuthorizationResponse.fromIntent(intent)
        val exception = AuthorizationException.fromIntent(intent)

        _uiState.update { GeminiAuthUiState.ExchangingToken }
        viewModelScope.launch {
            try {
                authManager.handleAuthorizationResponse(response, exception)
                // Optionally extract email from the id_token here.
                _uiState.update { GeminiAuthUiState.Authenticated(email = null) }
            } catch (ex: Exception) {
                val msg = ex.message ?: "Unknown error during token exchange."
                _uiState.update { GeminiAuthUiState.Error(msg) }
            }
        }
    }

    /** Sign out: wipe stored tokens and reset to [GeminiAuthUiState.Idle]. */
    fun signOut() {
        GeminiTokenManager.clearAll(authManagerFactory.applicationContext)
        _uiState.update { GeminiAuthUiState.Idle }
    }
}
