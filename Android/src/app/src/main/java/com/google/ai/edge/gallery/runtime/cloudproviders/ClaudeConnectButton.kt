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

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel

/**
 * Self-contained Compose component that drives the Claude OAuth manual-code flow.
 *
 * Drop this into any screen where Anthropic credentials are needed:
 * ```kotlin
 * ClaudeConnectButton()
 * ```
 *
 * States:
 * - **Idle** → "Connect Anthropic" button → opens browser
 * - **AwaitingCode** → [AlertDialog] with text field for the authorisation code
 * - **Exchanging** → disabled button + spinner while token exchange is in-flight
 * - **Authenticated** → "✓ Connected" text + "Disconnect" button
 * - **Error** → error message + "Retry" button
 */
@Composable
fun ClaudeConnectButton(
    viewModel: ClaudeOAuthViewModel = hiltViewModel(),
    modifier: Modifier = Modifier,
) {
    val uiState by viewModel.uiState.collectAsState()
    var codeInput by remember { mutableStateOf("") }

    Column(
        modifier            = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        when (val state = uiState) {

            is ClaudeAuthUiState.Idle -> {
                Button(
                    onClick  = { viewModel.startFlow() },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Connect Anthropic")
                }
            }

            is ClaudeAuthUiState.AwaitingCode -> {
                // Show spinner-style disabled button while browser is open
                Button(
                    onClick  = {},
                    enabled  = false,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    CircularProgressIndicator(
                        modifier  = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color     = MaterialTheme.colorScheme.onPrimary,
                    )
                    Spacer(modifier = Modifier.size(8.dp))
                    Text("Waiting for browser…")
                }

                // Code-entry dialog
                AlertDialog(
                    onDismissRequest = { viewModel.cancelFlow() },
                    title  = { Text("Paste Authorisation Code") },
                    text   = {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                "The Anthropic consent page shows a code. Copy it and paste it below.",
                                style = MaterialTheme.typography.bodySmall,
                            )
                            OutlinedTextField(
                                value         = codeInput,
                                onValueChange = { codeInput = it },
                                label         = { Text("Authorisation code") },
                                singleLine    = true,
                                modifier      = Modifier.fillMaxWidth(),
                            )
                        }
                    },
                    confirmButton = {
                        Button(
                            onClick  = {
                                viewModel.submitCode(codeInput)
                                codeInput = ""
                            },
                            enabled  = codeInput.isNotBlank(),
                        ) {
                            Text("Submit")
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { viewModel.cancelFlow(); codeInput = "" }) {
                            Text("Cancel")
                        }
                    },
                )
            }

            is ClaudeAuthUiState.Exchanging -> {
                Button(
                    onClick  = {},
                    enabled  = false,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    CircularProgressIndicator(
                        modifier  = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color     = MaterialTheme.colorScheme.onPrimary,
                    )
                    Spacer(modifier = Modifier.size(8.dp))
                    Text("Exchanging token…")
                }
            }

            is ClaudeAuthUiState.Authenticated -> {
                val label = if (state.hint.isNotBlank()) "✓ Connected as ${state.hint}" else "✓ Claude connected"
                Text(
                    text  = label,
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

            is ClaudeAuthUiState.Error -> {
                Text(
                    text      = state.message,
                    color     = MaterialTheme.colorScheme.error,
                    style     = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center,
                    modifier  = Modifier.fillMaxWidth(),
                )
                Spacer(modifier = Modifier.height(4.dp))
                Button(
                    onClick  = { viewModel.startFlow() },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Retry")
                }
            }
        }
    }
}
