/*
 * Copyright 2025 Google LLC
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

package com.google.ai.edge.gallery.ui.osmodules

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.google.ai.edge.gallery.ui.theme.absoluteBlack
import com.google.ai.edge.gallery.ui.theme.neonGreen

private data class ModelEntry(
  val name: String,
  val format: String,
  val sizeGb: Float,
  val isActive: Boolean,
  val isDownloaded: Boolean,
)

private val SAMPLE_MODELS = listOf(
  ModelEntry("Gemma-3-1B-IT", "LiteRT", 0.8f, true, true),
  ModelEntry("Gemma-3-4B-IT", "LiteRT", 2.4f, false, true),
  ModelEntry("Phi-3-mini-4k", "GGUF", 2.2f, false, false),
  ModelEntry("Mistral-7B-v0.3", "GGUF", 4.1f, false, false),
)

/** VENDING_MACHINE module — model management hub. */
@Composable
fun VendingMachineScreen(onOpenModelManager: () -> Unit) {
  Column(
    modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
  ) {
    Spacer(Modifier.height(16.dp))
    Text(
      "VENDING MACHINE",
      style = MaterialTheme.typography.headlineSmall,
      color = neonGreen,
      fontFamily = FontFamily.Monospace,
    )
    Text(
      "[ MODEL MANAGEMENT HUB ]",
      style = MaterialTheme.typography.labelSmall,
      color = neonGreen,
      fontFamily = FontFamily.Monospace,
    )
    Spacer(Modifier.height(8.dp))
    OutlinedButton(
      onClick = onOpenModelManager,
      modifier = Modifier.fillMaxWidth(),
      colors = ButtonDefaults.outlinedButtonColors(contentColor = neonGreen),
    ) {
      Text("OPEN FULL MODEL MANAGER", fontFamily = FontFamily.Monospace)
    }
    Spacer(Modifier.height(12.dp))
    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
      items(SAMPLE_MODELS) { model ->
        ModelCard(model)
      }
    }
  }
}

@Composable
private fun ModelCard(model: ModelEntry) {
  Card(
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    modifier = Modifier.fillMaxWidth().border(1.dp, neonGreen, RoundedCornerShape(4.dp)),
  ) {
    Row(
      modifier = Modifier.padding(12.dp),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
          Text(model.name, color = neonGreen, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.titleSmall)
          if (model.isActive) {
            Text("● ACTIVE", color = neonGreen, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.labelSmall)
          }
        }
        Text("[${model.format}] · ${model.sizeGb}GB", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
      }
      if (!model.isDownloaded) {
        Button(
          onClick = { /* TODO: trigger download */ },
          colors = ButtonDefaults.buttonColors(containerColor = neonGreen, contentColor = absoluteBlack),
        ) {
          Text("↓ GET", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.labelSmall)
        }
      } else if (!model.isActive) {
        OutlinedButton(
          onClick = { /* TODO: set active */ },
          colors = ButtonDefaults.outlinedButtonColors(contentColor = neonGreen),
        ) {
          Text("LOAD", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.labelSmall)
        }
      }
    }
  }
}
