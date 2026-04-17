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

package com.google.ai.edge.gallery.ui.osmodules

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.google.ai.edge.gallery.customtasks.agentchat.SkillManagerViewModel
import com.google.ai.edge.gallery.ui.theme.neonGreen

/**
 * SKILL_BOX module — full-screen skill configuration overview.
 *
 * Displays the list of installed skills with their toggle states.
 * Selecting/deselecting a skill toggles it immediately in the SkillManagerViewModel
 * so the change is reflected in the CHAT_BOX agent as well.
 */
@Composable
fun SkillBoxScreen(
  skillManagerViewModel: SkillManagerViewModel,
) {
  val uiState by skillManagerViewModel.uiState.collectAsState()

  Column(
    modifier = Modifier
      .fillMaxSize()
      .padding(horizontal = 16.dp),
  ) {
    Spacer(Modifier.height(16.dp))
    Text(
      "SKILL_BOX",
      style = MaterialTheme.typography.headlineSmall,
      color = neonGreen,
      fontFamily = FontFamily.Monospace,
    )
    Text(
      "[ SKILL CONFIGURATION ]",
      style = MaterialTheme.typography.labelSmall,
      color = neonGreen,
      fontFamily = FontFamily.Monospace,
    )
    Spacer(Modifier.height(8.dp))
    Text(
      "Toggle skills on/off. Active skills are available in CHAT_BOX.",
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      fontFamily = FontFamily.Monospace,
    )
    Spacer(Modifier.height(16.dp))

    if (uiState.skills.isEmpty()) {
      // Built-in tools section — always visible.
      Spacer(Modifier.height(4.dp))
      Text(
        "BUILT-IN TOOLS",
        style = MaterialTheme.typography.labelMedium,
        color = neonGreen.copy(alpha = 0.7f),
        fontFamily = FontFamily.Monospace,
      )
      Spacer(Modifier.height(8.dp))

      BuiltInToolCard(
        name = "BrainBox Memory",
        description = "Query & save neurons — always active",
      )
      Spacer(Modifier.height(8.dp))
      BuiltInToolCard(
        name = "FILE_BOX Workspace",
        description = "Read, write & manage code files — always active",
      )
      Spacer(Modifier.height(8.dp))
      BuiltInToolCard(
        name = "MSTR_CTRL Terminal",
        description = "Execute shell commands & scripts — always active",
      )

      Spacer(Modifier.height(24.dp))
      Text(
        "No user skills installed.\nOpen CHAT_BOX → Skills to add skills.",
        color = neonGreen.copy(alpha = 0.6f),
        fontFamily = FontFamily.Monospace,
        style = MaterialTheme.typography.bodyMedium,
      )
    } else {
      // Built-in tools section
      Spacer(Modifier.height(4.dp))
      Text(
        "BUILT-IN TOOLS",
        style = MaterialTheme.typography.labelMedium,
        color = neonGreen.copy(alpha = 0.7f),
        fontFamily = FontFamily.Monospace,
      )
      Spacer(Modifier.height(8.dp))

      // Built-in tool cards — these are always active and cannot be toggled off.
      BuiltInToolCard(
        name = "BrainBox Memory",
        description = "Query & save neurons — always active",
      )
      Spacer(Modifier.height(8.dp))
      BuiltInToolCard(
        name = "FILE_BOX Workspace",
        description = "Read, write & manage code files — always active",
      )
      Spacer(Modifier.height(8.dp))
      BuiltInToolCard(
        name = "MSTR_CTRL Terminal",
        description = "Execute shell commands & scripts — always active",
      )

      Spacer(Modifier.height(16.dp))
      Text(
        "INSTALLED SKILLS",
        style = MaterialTheme.typography.labelMedium,
        color = neonGreen.copy(alpha = 0.7f),
        fontFamily = FontFamily.Monospace,
      )
      Spacer(Modifier.height(8.dp))

      LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(uiState.skills) { skillState ->
          val skill = skillState.skill
          val isSelected = skill.selected
          SkillCard(
            name = skill.name,
            description = skill.description,
            isSelected = isSelected,
            onClick = {
              skillManagerViewModel.toggleSkillSelected(skill.name)
            },
          )
        }
      }
    }
  }
}

@Composable
private fun BuiltInToolCard(
  name: String,
  description: String,
) {
  Card(
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    modifier = Modifier
      .fillMaxWidth()
      .border(1.dp, neonGreen.copy(alpha = 0.4f), RoundedCornerShape(4.dp)),
  ) {
    Row(
      modifier = Modifier.padding(12.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Icon(
        Icons.Outlined.CheckCircle,
        contentDescription = null,
        tint = neonGreen,
        modifier = Modifier.size(20.dp),
      )
      Spacer(Modifier.width(12.dp))
      Column(modifier = Modifier.weight(1f)) {
        Text(
          name,
          color = neonGreen,
          fontFamily = FontFamily.Monospace,
          style = MaterialTheme.typography.titleSmall,
        )
        Text(
          description,
          fontFamily = FontFamily.Monospace,
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    }
  }
}

@Composable
private fun SkillCard(
  name: String,
  description: String,
  isSelected: Boolean,
  onClick: () -> Unit,
) {
  val borderColor = if (isSelected) neonGreen else neonGreen.copy(alpha = 0.3f)
  Card(
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    modifier = Modifier
      .fillMaxWidth()
      .border(1.dp, borderColor, RoundedCornerShape(4.dp))
      .clickable(onClick = onClick),
  ) {
    Row(
      modifier = Modifier.padding(12.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Icon(
        if (isSelected) Icons.Outlined.CheckCircle else Icons.Outlined.RadioButtonUnchecked,
        contentDescription = null,
        tint = if (isSelected) neonGreen else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.size(20.dp),
      )
      Spacer(Modifier.width(12.dp))
      Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
          name,
          color = if (isSelected) neonGreen else MaterialTheme.colorScheme.onSurface,
          fontFamily = FontFamily.Monospace,
          style = MaterialTheme.typography.titleSmall,
        )
        if (description.isNotBlank()) {
          Text(
            description,
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
          )
        }
      }
    }
  }
}
