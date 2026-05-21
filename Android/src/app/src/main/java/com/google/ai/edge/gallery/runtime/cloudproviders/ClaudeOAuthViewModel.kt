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
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.ai.edge.gallery.data.providers.ProviderRegistry
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

// ── UI State ──────────────────────────────────────────────────────────────────

/** All possible states for the Claude OAuth connect flow. */
sealed interface ClaudeAuthUiState {
    data object Idle : ClaudeAuthUiState
    /** Browser opened; waiting for user to paste the code. */
    data object AwaitingCode : ClaudeAuthUiState
    /** Code received; token exchange in-flight. */
    data object Exchanging : ClaudeAuthUiState
    /** Successfully authenticated. [hint] is empty when the token has no user info. */
    data class Authenticated(val hint: String = "") : ClaudeAuthUiState
    data class Error(val message: String) : ClaudeAuthUiState
}

// ── ViewModel ─────────────────────────────────────────────────────────────────

/**
 * Hilt ViewModel driving [ClaudeConnectButton] state.
 *
 * Uses [ClaudeAuthManager] for the PKCE flow and [ClaudeCredentialStore] for
 * persistence.  On sign-out, invalidates all cached [ProviderRegistry] entries so
 * stale AnthropicProvider instances are flushed.
 */
@HiltViewModel
class ClaudeOAuthViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
) : ViewModel() {

    private val _uiState = MutableStateFlow<ClaudeAuthUiState>(
        if (ClaudeCredentialStore.hasValidCredentials(appContext))
            ClaudeAuthUiState.Authenticated()
        else
            ClaudeAuthUiState.Idle
    )
    val uiState: StateFlow<ClaudeAuthUiState> = _uiState.asStateFlow()

    private val authManager = ClaudeAuthManager(appContext)

    // ── Public API ─────────────────────────────────────────────────────────────

    /** Kick off the OAuth browser flow. */
    fun startFlow() {
        viewModelScope.launch {
            _uiState.update { ClaudeAuthUiState.AwaitingCode }
            try {
                val creds = authManager.startOAuthFlow()
                // startOAuthFlow() suspends until code is submitted; then it transitions
                // through AwaitingCode → Exchanging internally by checking state.
                _uiState.update { ClaudeAuthUiState.Authenticated() }
                ProviderRegistry.invalidate("anthropic", creds.accessToken)
                ProviderRegistry.invalidateAll()
            } catch (e: Exception) {
                val msg = e.message ?: "Unknown error during sign-in."
                _uiState.update { ClaudeAuthUiState.Error(msg) }
            }
        }
    }

    /** Called by the UI when the user submits the code from the consent page. */
    fun submitCode(code: String) {
        _uiState.update { ClaudeAuthUiState.Exchanging }
        authManager.submitAuthorizationCode(code)
    }

    /** Cancel an in-progress flow and return to Idle. */
    fun cancelFlow() {
        authManager.cancelFlow()
        _uiState.update { ClaudeAuthUiState.Idle }
    }

    /** Sign out and invalidate cached providers. */
    fun signOut() {
        authManager.signOut()
        ProviderRegistry.invalidateAll()
        _uiState.update { ClaudeAuthUiState.Idle }
    }
}
