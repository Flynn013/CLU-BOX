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

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel

/**
 * Self-contained Compose component that drives the full Google OAuth PKCE flow.
 *
 * Drop this into any screen where you need the user to authorize Gemini access:
 * ```kotlin
 * GeminiConnectButton()
 * ```
 *
 * The ViewModel is scoped to the NavBackStackEntry (or the nearest Hilt component)
 * so auth state survives configuration changes.
 */
@Composable
fun GeminiConnectButton(
    viewModel: GeminiAuthViewModel = hiltViewModel(),
    modifier: Modifier = Modifier,
) {
    val uiState by viewModel.uiState.collectAsState()

    // Register the Activity Result launcher once, passing results back to the ViewModel.
    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        viewModel.handleActivityResult(result)
    }

    Column(
        modifier           = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        when (val state = uiState) {

            is GeminiAuthUiState.Idle -> {
                Button(
                    onClick = { launcher.launch(viewModel.buildAuthIntent()) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Connect Gemini")
                }
            }

            is GeminiAuthUiState.AwaitingBrowser,
            is GeminiAuthUiState.ExchangingToken -> {
                Button(
                    onClick  = {},
                    enabled  = false,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                    Spacer(modifier = Modifier.size(8.dp))
                    Text(
                        text = if (state is GeminiAuthUiState.AwaitingBrowser)
                            "Waiting for sign-in…"
                        else
                            "Exchanging token…"
                    )
                }
            }

            is GeminiAuthUiState.Authenticated -> {
                Text(
                    text = if (state.email != null)
                        "✓ Connected as ${state.email}"
                    else
                        "✓ Gemini connected",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(modifier = Modifier.height(4.dp))
                OutlinedButton(
                    onClick  = { viewModel.signOut() },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Disconnect")
                }
            }

            is GeminiAuthUiState.Error -> {
                Text(
                    text      = state.message,
                    color     = MaterialTheme.colorScheme.error,
                    style     = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center,
                    modifier  = Modifier.fillMaxWidth(),
                )
                Spacer(modifier = Modifier.height(4.dp))
                Button(
                    onClick  = { launcher.launch(viewModel.buildAuthIntent()) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Retry")
                }
            }
        }
    }
}
