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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.google.ai.edge.gallery.ui.theme.neonGreen

private data class SkillManifest(val name: String, val description: String, var enabled: Boolean)

/** SKILL_BOX module — view, toggle, edit, and create custom Agent Skills. */
@Composable
fun SkillBoxScreen() {
  val skills = remember {
    mutableStateListOf(
      SkillManifest("Web Fetch", "Fetch and summarize a web URL.", true),
      SkillManifest("Code Exec", "Execute a sandboxed code snippet.", false),
      SkillManifest("Calendar", "Read/write calendar events.", false),
      SkillManifest("File Search", "Search local documents.", true),
    )
  }

  Column(
    modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
  ) {
    Spacer(Modifier.height(16.dp))
    Text(
      "SKILL_BOX",
      style = MaterialTheme.typography.headlineSmall,
      color = neonGreen,
      fontFamily = FontFamily.Monospace,
    )
    Text(
      "[ AGENT TOOL MANAGEMENT ]",
      style = MaterialTheme.typography.labelSmall,
      color = neonGreen,
      fontFamily = FontFamily.Monospace,
    )
    Spacer(Modifier.height(8.dp))
    OutlinedButton(
      onClick = { /* TODO: open create-skill sheet */ },
      colors = ButtonDefaults.outlinedButtonColors(contentColor = neonGreen),
      modifier = Modifier.fillMaxWidth(),
    ) {
      Text("+ NEW SKILL", fontFamily = FontFamily.Monospace)
    }
    Spacer(Modifier.height(12.dp))
    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
      itemsIndexed(skills) { index, skill ->
        SkillCard(
          skill = skill,
          onToggle = { skills[index] = skill.copy(enabled = !skill.enabled) },
          onEdit = { /* TODO: open edit sheet */ },
        )
      }
    }
  }
}

@Composable
private fun SkillCard(skill: SkillManifest, onToggle: () -> Unit, onEdit: () -> Unit) {
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
        Text(skill.name, color = neonGreen, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.titleSmall)
        Text(skill.description, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
      }
      Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(
          onClick = onEdit,
          colors = ButtonDefaults.outlinedButtonColors(contentColor = neonGreen),
        ) { Text("EDIT", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.labelSmall) }
        Switch(
          checked = skill.enabled,
          onCheckedChange = { onToggle() },
          colors = SwitchDefaults.colors(
            checkedThumbColor = neonGreen,
            checkedTrackColor = neonGreen.copy(alpha = 0.3f),
            uncheckedThumbColor = MaterialTheme.colorScheme.onSurfaceVariant,
          ),
        )
      }
    }
  }
}
