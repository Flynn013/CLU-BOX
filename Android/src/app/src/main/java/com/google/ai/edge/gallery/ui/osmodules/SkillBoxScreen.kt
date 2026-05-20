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

import androidx.compose.foundation.border
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.google.ai.edge.gallery.customtasks.agentchat.SkillManagerViewModel
import com.google.ai.edge.gallery.ui.theme.absoluteBlack
import com.google.ai.edge.gallery.ui.theme.neonGreen

/**
 * SKILL_BOX module — view, toggle, create, edit and delete agent skills.
 *
 * Built-in skills (registered by SkillRegistry) are shown with a toggle only.
 * Custom skills additionally expose Edit and Delete actions.
 */
@Composable
fun SkillBoxScreen(
  skillManagerViewModel: SkillManagerViewModel,
) {
  // Populate skills on first entry.
  LaunchedEffect(Unit) { skillManagerViewModel.loadSkills {} }

  val uiState by skillManagerViewModel.uiState.collectAsState()
  val builtInSkills = uiState.skills.filter { it.skill.builtIn }
  val customSkills = uiState.skills.filter { !it.skill.builtIn }

  // Create / Edit dialog state
  var showEditDialog by remember { mutableStateOf(false) }
  var editIndex by remember { mutableStateOf(-1) }  // -1 = create new
  var editName by remember { mutableStateOf("") }
  var editDesc by remember { mutableStateOf("") }
  var editInstructions by remember { mutableStateOf("") }
  var editError by remember { mutableStateOf("") }

  // Delete confirmation state
  var deleteTarget by remember { mutableStateOf<String?>(null) }

  // Create / Edit dialog
  if (showEditDialog) {
    AlertDialog(
      onDismissRequest = { showEditDialog = false; editError = "" },
      title = {
        Text(
          if (editIndex < 0) "NEW SKILL" else "EDIT SKILL",
          fontFamily = FontFamily.Monospace,
          color = neonGreen,
        )
      },
      text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
          OutlinedTextField(
            value = editName,
            onValueChange = { editName = it },
            label = { Text("Name", fontFamily = FontFamily.Monospace) },
            enabled = editIndex < 0,
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
          )
          OutlinedTextField(
            value = editDesc,
            onValueChange = { editDesc = it },
            label = { Text("Description", fontFamily = FontFamily.Monospace) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
          )
          OutlinedTextField(
            value = editInstructions,
            onValueChange = { editInstructions = it },
            label = { Text("Instructions", fontFamily = FontFamily.Monospace) },
            singleLine = false,
            minLines = 3,
            maxLines = 8,
            modifier = Modifier.fillMaxWidth(),
          )
          if (editError.isNotEmpty()) {
            Text(
              editError,
              color = MaterialTheme.colorScheme.error,
              fontFamily = FontFamily.Monospace,
              style = MaterialTheme.typography.bodySmall,
            )
          }
        }
      },
      confirmButton = {
        TextButton(onClick = {
          if (editName.isBlank()) {
            editError = "Name is required."
            return@TextButton
          }
          skillManagerViewModel.saveSkillEdit(
            index = editIndex,
            name = editName.trim(),
            description = editDesc.trim(),
            instructions = editInstructions.trim(),
            scriptsContent = emptyMap(),
            onSuccess = { showEditDialog = false; editError = "" },
            onError = { err -> editError = err },
          )
        }) { Text("SAVE", color = neonGreen, fontFamily = FontFamily.Monospace) }
      },
      dismissButton = {
        TextButton(onClick = { showEditDialog = false; editError = "" }) {
          Text("CANCEL", fontFamily = FontFamily.Monospace)
        }
      },
    )
  }

  // Delete confirmation dialog
  deleteTarget?.let { skillName ->
    AlertDialog(
      onDismissRequest = { deleteTarget = null },
      title = { Text("DELETE SKILL?", fontFamily = FontFamily.Monospace, color = neonGreen) },
      text = { Text("\"$skillName\" will be permanently removed.", fontFamily = FontFamily.Monospace) },
      confirmButton = {
        TextButton(onClick = {
          skillManagerViewModel.deleteSkill(skillName)
          deleteTarget = null
        }) { Text("DELETE", color = MaterialTheme.colorScheme.error, fontFamily = FontFamily.Monospace) }
      },
      dismissButton = {
        TextButton(onClick = { deleteTarget = null }) {
          Text("CANCEL", fontFamily = FontFamily.Monospace)
        }
      },
    )
  }

  Scaffold(
    containerColor = absoluteBlack,
    floatingActionButton = {
      FloatingActionButton(
        onClick = {
          editIndex = -1
          editName = ""
          editDesc = ""
          editInstructions = ""
          editError = ""
          showEditDialog = true
        },
        containerColor = neonGreen,
        contentColor = absoluteBlack,
      ) {
        Icon(Icons.Outlined.Add, contentDescription = "New Skill")
      }
    },
  ) { innerPadding ->
    LazyColumn(
      modifier = Modifier
        .fillMaxSize()
        .padding(innerPadding)
        .padding(horizontal = 16.dp),
      verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      item {
        Spacer(Modifier.height(12.dp))
        Text(
          "SKILL_BOX",
          style = MaterialTheme.typography.headlineSmall,
          color = neonGreen,
          fontFamily = FontFamily.Monospace,
        )
        Text(
          "[ AGENT CAPABILITIES ]",
          style = MaterialTheme.typography.labelSmall,
          color = neonGreen,
          fontFamily = FontFamily.Monospace,
        )
        Spacer(Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
          Text(
            "TOTAL ${uiState.skills.size}",
            style = MaterialTheme.typography.labelMedium,
            color = neonGreen.copy(alpha = 0.7f),
            fontFamily = FontFamily.Monospace,
          )
          Text(
            "ACTIVE ${uiState.skills.count { it.skill.selected }}",
            style = MaterialTheme.typography.labelMedium,
            color = neonGreen,
            fontFamily = FontFamily.Monospace,
          )
          Text(
            "CUSTOM ${customSkills.size}",
            style = MaterialTheme.typography.labelMedium,
            color = neonGreen.copy(alpha = 0.7f),
            fontFamily = FontFamily.Monospace,
          )
        }
        Spacer(Modifier.height(12.dp))
      }

      if (builtInSkills.isNotEmpty()) {
        item {
          Text(
            "BUILT-IN",
            style = MaterialTheme.typography.labelMedium,
            color = neonGreen.copy(alpha = 0.7f),
            fontFamily = FontFamily.Monospace,
          )
          Spacer(Modifier.height(4.dp))
        }
        items(builtInSkills) { skillState ->
          SkillRow(
            name = skillState.skill.name,
            description = skillState.skill.description,
            isSelected = skillState.skill.selected,
            isBuiltIn = true,
            onToggle = { skillManagerViewModel.toggleSkillSelected(skillState.skill.name) },
            onEdit = null,
            onDelete = null,
          )
        }
        item { Spacer(Modifier.height(8.dp)) }
      }

      item {
        Text(
          "CUSTOM",
          style = MaterialTheme.typography.labelMedium,
          color = neonGreen.copy(alpha = 0.7f),
          fontFamily = FontFamily.Monospace,
        )
        Spacer(Modifier.height(4.dp))
      }

      if (customSkills.isEmpty()) {
        item {
          Box(
            modifier = Modifier
              .fillMaxWidth()
              .padding(vertical = 16.dp),
            contentAlignment = Alignment.Center,
          ) {
            Text(
              "No custom skills. Tap + to create one.",
              color = neonGreen.copy(alpha = 0.5f),
              fontFamily = FontFamily.Monospace,
              style = MaterialTheme.typography.bodyMedium,
            )
          }
        }
      } else {
        items(customSkills) { skillState ->
          val skillIndex = uiState.skills.indexOf(skillState)
          SkillRow(
            name = skillState.skill.name,
            description = skillState.skill.description,
            isSelected = skillState.skill.selected,
            isBuiltIn = false,
            onToggle = { skillManagerViewModel.toggleSkillSelected(skillState.skill.name) },
            onEdit = {
              editIndex = skillIndex
              editName = skillState.skill.name
              editDesc = skillState.skill.description
              editInstructions = skillState.skill.instructions
              editError = ""
              showEditDialog = true
            },
            onDelete = { deleteTarget = skillState.skill.name },
          )
        }
      }

      item { Spacer(Modifier.height(80.dp)) } // FAB clearance
    }
  }
}

@Composable
private fun SkillRow(
  name: String,
  description: String,
  isSelected: Boolean,
  isBuiltIn: Boolean,
  onToggle: () -> Unit,
  onEdit: (() -> Unit)?,
  onDelete: (() -> Unit)?,
) {
  val borderColor = if (isSelected) neonGreen else neonGreen.copy(alpha = 0.3f)
  Card(
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    modifier = Modifier
      .fillMaxWidth()
      .border(1.dp, borderColor, RoundedCornerShape(4.dp)),
  ) {
    Row(
      modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Column(
        modifier = Modifier.weight(1f),
        verticalArrangement = Arrangement.spacedBy(2.dp),
      ) {
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
      if (!isBuiltIn) {
        onEdit?.let { edit ->
          IconButton(onClick = edit, modifier = Modifier.size(36.dp)) {
            Icon(
              Icons.Outlined.Edit,
              contentDescription = "Edit",
              tint = neonGreen,
              modifier = Modifier.size(18.dp),
            )
          }
        }
        onDelete?.let { delete ->
          IconButton(onClick = delete, modifier = Modifier.size(36.dp)) {
            Icon(
              Icons.Outlined.Delete,
              contentDescription = "Delete",
              tint = MaterialTheme.colorScheme.error,
              modifier = Modifier.size(18.dp),
            )
          }
        }
      }
      Switch(
        checked = isSelected,
        onCheckedChange = { onToggle() },
        colors = SwitchDefaults.colors(
          checkedThumbColor = absoluteBlack,
          checkedTrackColor = neonGreen,
        ),
      )
    }
  }
}
