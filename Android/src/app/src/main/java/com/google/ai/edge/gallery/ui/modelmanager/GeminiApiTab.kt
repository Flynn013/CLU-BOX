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

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.google.ai.edge.gallery.runtime.geminicloud.GeminiApiKeyStore
import com.google.ai.edge.gallery.runtime.geminicloud.GeminiModelInfo
import com.google.ai.edge.gallery.runtime.geminicloud.fetchGeminiModels
import com.google.ai.edge.gallery.ui.theme.neonGreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The GEMINI tab content inside the Vending Machine (GlobalModelManager).
 *
 * Provides:
 *  - An API key entry field (persisted via [GeminiApiKeyStore])
 *  - A "Fetch Models" button that queries the Gemini REST API
 *  - A scrollable list of available Gemini models with selection
 *  - An "Add to Chat" button that registers the selected model
 */
@Composable
fun GeminiApiTab(
  viewModel: ModelManagerViewModel,
  onModelAdded: (modelId: String, displayName: String) -> Unit,
  modifier: Modifier = Modifier,
) {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()

  // ── State ────────────────────────────────────────────────────────────────
  var apiKey by remember { mutableStateOf(GeminiApiKeyStore.getApiKey(context) ?: "") }
  var apiKeyVisible by remember { mutableStateOf(false) }
  var isFetching by remember { mutableStateOf(false) }
  var fetchError by remember { mutableStateOf("") }
  val availableModels = remember { mutableStateListOf<GeminiModelInfo>() }
  var selectedModelId by remember { mutableStateOf<String?>(null) }
  val addedModelIds = remember { mutableStateListOf<String>() }

  // Pre-populate already-added Gemini models so the UI shows their checkmarks.
  LaunchedEffect(Unit) {
    val persisted = viewModel.dataStoreRepository.readGeminiCloudModels()
    addedModelIds.addAll(persisted.map { it.modelId })
  }

  Column(
    modifier = modifier
      .fillMaxWidth()
      .padding(horizontal = 16.dp, vertical = 8.dp),
    verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {

    // ── Section header ───────────────────────────────────────────────────
    Row(
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      Icon(
        Icons.Outlined.Cloud,
        contentDescription = null,
        tint = neonGreen,
        modifier = Modifier.size(20.dp),
      )
      Text(
        "CLOUD_CLU — Gemini API",
        style = MaterialTheme.typography.titleMedium,
        fontFamily = FontFamily.Monospace,
        color = neonGreen,
      )
    }

    Text(
      "Enter your Google AI Studio API key to fetch and add Gemini cloud models to the agentic chat.",
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
    )

    // ── API Key field ────────────────────────────────────────────────────
    OutlinedTextField(
      value = apiKey,
      onValueChange = { apiKey = it; fetchError = "" },
      label = {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
          Icon(Icons.Outlined.Key, contentDescription = null, modifier = Modifier.size(14.dp))
          Text("Gemini API Key")
        }
      },
      placeholder = { Text("AIza…", fontFamily = FontFamily.Monospace) },
      singleLine = true,
      visualTransformation = if (apiKeyVisible) VisualTransformation.None else PasswordVisualTransformation(),
      keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
      modifier = Modifier.fillMaxWidth(),
      colors = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = neonGreen,
        focusedLabelColor = neonGreen,
        cursorColor = neonGreen,
      ),
      trailingIcon = {
        IconButton(onClick = { apiKeyVisible = !apiKeyVisible }) {
          Text(
            if (apiKeyVisible) "HIDE" else "SHOW",
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            color = neonGreen,
          )
        }
      },
      isError = fetchError.isNotEmpty(),
      supportingText = if (fetchError.isNotEmpty()) {
        { Text(fetchError, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall) }
      } else null,
    )

    // ── Fetch button ─────────────────────────────────────────────────────
    Button(
      onClick = {
        val trimmedKey = apiKey.trim()
        if (trimmedKey.isBlank()) {
          fetchError = "API key cannot be empty"
          return@Button
        }
        // Persist the key.
        GeminiApiKeyStore.setApiKey(context, trimmedKey)
        apiKey = trimmedKey

        fetchError = ""
        isFetching = true
        availableModels.clear()
        selectedModelId = null

        scope.launch {
          val result = withContext(Dispatchers.IO) { fetchGeminiModels(trimmedKey) }
          isFetching = false
          result.fold(
            onSuccess = { models ->
              availableModels.addAll(models)
              if (models.isEmpty()) fetchError = "No chat-capable models found for this key."
            },
            onFailure = { e ->
              fetchError = e.message ?: "Failed to fetch models"
            },
          )
        }
      },
      enabled = !isFetching,
      colors = ButtonDefaults.buttonColors(
        containerColor = neonGreen,
        contentColor = MaterialTheme.colorScheme.surface,
        disabledContainerColor = neonGreen.copy(alpha = 0.4f),
      ),
      modifier = Modifier.fillMaxWidth(),
    ) {
      if (isFetching) {
        CircularProgressIndicator(
          modifier = Modifier.size(16.dp),
          color = MaterialTheme.colorScheme.surface,
          strokeWidth = 2.dp,
        )
        Spacer(Modifier.width(8.dp))
        Text("Fetching…", fontFamily = FontFamily.Monospace)
      } else {
        Icon(Icons.Outlined.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text("Fetch Available Models", fontFamily = FontFamily.Monospace)
      }
    }

    // ── Model list ───────────────────────────────────────────────────────
    AnimatedVisibility(
      visible = availableModels.isNotEmpty(),
      enter = fadeIn() + expandVertically(),
      exit = fadeOut() + shrinkVertically(),
    ) {
      Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
          "${availableModels.size} models available — tap to select",
          style = MaterialTheme.typography.labelSmall,
          fontFamily = FontFamily.Monospace,
          color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
        )

        availableModels.forEach { model ->
          val isSelected = selectedModelId == model.modelId
          val isAdded = addedModelIds.contains(model.modelId)

          GeminiModelRow(
            model = model,
            isSelected = isSelected,
            isAdded = isAdded,
            onClick = { if (!isAdded) selectedModelId = if (isSelected) null else model.modelId },
          )
        }

        // ── Add button ───────────────────────────────────────────────
        val sel = selectedModelId
        if (sel != null) {
          val selModel = availableModels.find { it.modelId == sel }
          if (selModel != null) {
            Spacer(Modifier.height(4.dp))
            Button(
              onClick = {
                onModelAdded(selModel.modelId, selModel.displayName)
                addedModelIds.add(selModel.modelId)
                selectedModelId = null
              },
              colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
              ),
              modifier = Modifier.fillMaxWidth(),
            ) {
              Text(
                "Add \"${selModel.displayName}\" to CLOUD_CLU",
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
              )
            }
          }
        }
      }
    }

    // ── Already-added models summary ─────────────────────────────────────
    if (availableModels.isEmpty() && addedModelIds.isNotEmpty()) {
      Text(
        "${addedModelIds.size} Gemini model(s) active in CLOUD_CLU. Fetch models to add more.",
        style = MaterialTheme.typography.bodySmall,
        fontFamily = FontFamily.Monospace,
        color = neonGreen.copy(alpha = 0.8f),
      )
    }
  }
}

@Composable
private fun GeminiModelRow(
  model: GeminiModelInfo,
  isSelected: Boolean,
  isAdded: Boolean,
  onClick: () -> Unit,
) {
  val borderColor = when {
    isAdded -> neonGreen
    isSelected -> MaterialTheme.colorScheme.primary
    else -> MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)
  }
  val bgColor = when {
    isAdded -> neonGreen.copy(alpha = 0.08f)
    isSelected -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
    else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
  }

  Box(
    modifier = Modifier
      .fillMaxWidth()
      .clip(RoundedCornerShape(8.dp))
      .background(bgColor)
      .border(1.dp, borderColor, RoundedCornerShape(8.dp))
      .clickable(enabled = !isAdded, onClick = onClick)
      .padding(horizontal = 12.dp, vertical = 8.dp),
  ) {
    Row(
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.SpaceBetween,
      modifier = Modifier.fillMaxWidth(),
    ) {
      Column(modifier = Modifier.weight(1f)) {
        Text(
          model.displayName,
          style = MaterialTheme.typography.bodyMedium,
          fontFamily = FontFamily.Monospace,
          fontWeight = if (isSelected || isAdded) FontWeight.Bold else FontWeight.Normal,
          color = if (isAdded) neonGreen else MaterialTheme.colorScheme.onSurface,
        )
        Text(
          model.modelId,
          style = MaterialTheme.typography.labelSmall,
          fontFamily = FontFamily.Monospace,
          color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
        )
      }

      when {
        isAdded -> Icon(
          Icons.Filled.CheckCircle,
          contentDescription = "Added",
          tint = neonGreen,
          modifier = Modifier.size(20.dp),
        )
        isSelected -> Icon(
          Icons.Filled.CheckCircle,
          contentDescription = "Selected",
          tint = MaterialTheme.colorScheme.primary,
          modifier = Modifier.size(20.dp),
        )
      }
    }
  }
}
