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

package com.google.ai.edge.gallery.ui.modelmanager

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
 * Dialog for manually adding a BESPOKE API model to the VENDING_MACHINE.
 *
 * Fields:
 * - Model Name (custom label)
 * - API Endpoint (e.g. Google Gemini, OpenAI-compatible)
 * - API Key (stored securely in EncryptedSharedPreferences)
 * - Context Window Size (default 32k)
 */
@Composable
fun AddApiModelDialog(
  onDismiss: () -> Unit,
  onModelAdded: (name: String, endpoint: String, apiKey: String, contextWindowSize: Int) -> Unit,
) {
  var modelName by remember { mutableStateOf("") }
  var apiEndpoint by remember { mutableStateOf("") }
  var apiKey by remember { mutableStateOf("") }
  var contextWindowText by remember { mutableStateOf("32768") }
  var errorText by remember { mutableStateOf("") }

  AlertDialog(
    onDismissRequest = onDismiss,
    title = {
      Text(
        text = "Add API Model",
        style = MaterialTheme.typography.titleMedium,
      )
    },
    text = {
      Column(modifier = Modifier.padding(top = 4.dp)) {
        Text(
          text = "Add a Gemini-compatible API model. Your API key is stored securely on-device.",
          style = MaterialTheme.typography.bodyMedium,
        )

        Spacer(modifier = Modifier.height(12.dp))

        OutlinedTextField(
          value = modelName,
          onValueChange = { modelName = it; errorText = "" },
          label = { Text("Model Name") },
          placeholder = { Text("My Custom Model") },
          singleLine = true,
          modifier = Modifier.fillMaxWidth(),
        )

        Spacer(modifier = Modifier.height(8.dp))

        OutlinedTextField(
          value = apiEndpoint,
          onValueChange = { apiEndpoint = it; errorText = "" },
          label = { Text("API Endpoint") },
          placeholder = { Text("https://generativelanguage.googleapis.com/v1beta/models/gemini-2.0-flash") },
          singleLine = true,
          modifier = Modifier.fillMaxWidth(),
        )

        Spacer(modifier = Modifier.height(8.dp))

        OutlinedTextField(
          value = apiKey,
          onValueChange = { apiKey = it; errorText = "" },
          label = { Text("API Key") },
          placeholder = { Text("AIza…") },
          singleLine = true,
          visualTransformation = PasswordVisualTransformation(),
          keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
          modifier = Modifier.fillMaxWidth(),
        )

        Spacer(modifier = Modifier.height(8.dp))

        OutlinedTextField(
          value = contextWindowText,
          onValueChange = { contextWindowText = it; errorText = "" },
          label = { Text("Context Window Size") },
          placeholder = { Text("32768") },
          singleLine = true,
          keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
          modifier = Modifier.fillMaxWidth(),
        )

        if (errorText.isNotEmpty()) {
          Spacer(modifier = Modifier.height(8.dp))
          Text(
            text = errorText,
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall,
          )
        }
      }
    },
    confirmButton = {
      TextButton(
        onClick = {
          val trimmedName = modelName.trim()
          val trimmedEndpoint = apiEndpoint.trim()
          val trimmedKey = apiKey.trim()
          val contextWindow = contextWindowText.trim().toIntOrNull() ?: 32768

          when {
            trimmedName.isBlank() -> errorText = "Model name cannot be empty"
            trimmedName.contains("/") -> errorText = "Model name cannot contain '/'"
            trimmedEndpoint.isBlank() -> errorText = "API endpoint cannot be empty"
            trimmedKey.isBlank() -> errorText = "API key cannot be empty"
            contextWindow < 1024 -> errorText = "Context window must be at least 1024"
            else -> onModelAdded(trimmedName, trimmedEndpoint, trimmedKey, contextWindow)
          }
        },
      ) {
        Text("Add")
      }
    },
    dismissButton = {
      TextButton(onClick = onDismiss) {
        Text("Cancel")
      }
    },
  )
}
