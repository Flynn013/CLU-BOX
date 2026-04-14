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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.google.ai.edge.gallery.ui.theme.absoluteBlack
import com.google.ai.edge.gallery.ui.theme.neonGreen

private data class AiGame(val name: String, val description: String)

private val AI_GAMES = listOf(
  AiGame("Neural Chess", "Strategic board game with adaptive LLM opponent."),
  AiGame("Code Breaker", "Guess the 4-digit code — LLM defends and hints."),
  AiGame("Riddle Forge", "LLM generates escalating logic riddles to solve."),
  AiGame("Story Duel", "Turn-based collaborative storytelling — then vote."),
  AiGame("Cipher Run", "Encrypt/decrypt messages against the clock."),
)

/** THE_GRID module — AI simulation game list with Initialize Match actions. */
@Composable
fun TheGridScreen() {
  Column(
    modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
  ) {
    Spacer(Modifier.height(16.dp))
    Text(
      "THE GRID",
      style = MaterialTheme.typography.headlineSmall,
      color = neonGreen,
      fontFamily = FontFamily.Monospace,
    )
    Text(
      "[ AI SIMULATION ARENA ]",
      style = MaterialTheme.typography.labelSmall,
      color = neonGreen,
      fontFamily = FontFamily.Monospace,
    )
    Spacer(Modifier.height(16.dp))
    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
      items(AI_GAMES) { game ->
        GameCard(game)
      }
    }
  }
}

@Composable
private fun GameCard(game: AiGame) {
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
        Text(
          game.name,
          color = neonGreen,
          fontFamily = FontFamily.Monospace,
          style = MaterialTheme.typography.titleSmall,
        )
        Text(
          game.description,
          fontFamily = FontFamily.Monospace,
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
      Button(
        onClick = { /* TODO: launch game session */ },
        colors = ButtonDefaults.buttonColors(containerColor = neonGreen, contentColor = absoluteBlack),
        modifier = Modifier.padding(start = 8.dp),
      ) {
        Text("▶ INIT", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.labelSmall)
      }
    }
  }
}
