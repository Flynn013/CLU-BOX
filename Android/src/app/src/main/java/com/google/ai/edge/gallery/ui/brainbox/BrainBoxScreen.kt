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

package com.google.ai.edge.gallery.ui.brainbox

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.google.ai.edge.gallery.ui.theme.neonGreen

/** Placeholder screen for the BrainBox local knowledge graph. */
@Composable
fun BrainBoxScreen() {
  Column(
    modifier = Modifier.fillMaxSize().padding(32.dp),
    verticalArrangement = Arrangement.Center,
    horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    Text(
      text = "BRAINBOX",
      style = MaterialTheme.typography.headlineLarge,
      color = neonGreen,
      fontFamily = FontFamily.Monospace,
    )
    Spacer(modifier = Modifier.height(16.dp))
    Text(
      text = "[ LOCAL KNOWLEDGE GRAPH ]",
      style = MaterialTheme.typography.titleSmall,
      color = neonGreen,
      fontFamily = FontFamily.Monospace,
      textAlign = TextAlign.Center,
    )
    Spacer(modifier = Modifier.height(24.dp))
    Text(
      text = "GraphRAG memory system\ncoming online...",
      style = MaterialTheme.typography.bodyMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      fontFamily = FontFamily.Monospace,
      textAlign = TextAlign.Center,
    )
  }
}
