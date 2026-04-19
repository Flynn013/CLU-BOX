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

package com.google.ai.edge.gallery.runtime.geminicloud

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

/**
 * A centered modal dialog that prompts the user for their Gemini API key.
 * Displayed the first time a user selects the "Cloud Node" model from the
 * dropdown (or whenever no valid key is stored).
 *
 * The key is persisted via [GeminiApiKeyStore] into EncryptedSharedPreferences.
 */
@Composable
fun GeminiApiKeyDialog(
  onDismiss: () -> Unit,
  onApiKeySaved: (String) -> Unit,
) {
  var apiKey by remember { mutableStateOf("") }
  var errorText by remember { mutableStateOf("") }

  AlertDialog(
    onDismissRequest = onDismiss,
    title = {
      Text(
        text = "Cloud Node — API Key",
        style = MaterialTheme.typography.titleMedium,
      )
    },
    text = {
      Column(modifier = Modifier.padding(top = 4.dp)) {
        Text(
          text = "Enter your Google Gemini API key to enable the Cloud Node. " +
            "Your key is stored securely on-device and never leaves this app.",
          style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(modifier = Modifier.height(16.dp))
        OutlinedTextField(
          value = apiKey,
          onValueChange = {
            apiKey = it
            errorText = ""
          },
          label = { Text("API Key") },
          placeholder = { Text("AIza…") },
          singleLine = true,
          visualTransformation = PasswordVisualTransformation(),
          keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
          modifier = Modifier.fillMaxWidth(),
          isError = errorText.isNotEmpty(),
          supportingText = if (errorText.isNotEmpty()) {
            { Text(text = errorText, color = MaterialTheme.colorScheme.error) }
          } else {
            null
          },
        )
      }
    },
    confirmButton = {
      TextButton(
        onClick = {
          val trimmed = apiKey.trim()
          if (trimmed.isBlank()) {
            errorText = "API key cannot be empty"
            return@TextButton
          }
          onApiKeySaved(trimmed)
        },
      ) {
        Text("Save")
      }
    },
    dismissButton = {
      TextButton(onClick = onDismiss) {
        Text("Cancel")
      }
    },
  )
}
