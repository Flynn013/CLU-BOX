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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import android.content.Context
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.ai.edge.gallery.data.brainbox.GraphDatabase
import com.google.ai.edge.gallery.data.scdlbox.ScdlBoxEntity
import com.google.ai.edge.gallery.data.scdlbox.ScdlBoxManager
import com.google.ai.edge.gallery.ui.theme.absoluteBlack
import com.google.ai.edge.gallery.ui.theme.neonGreen
import kotlinx.coroutines.launch
import java.util.UUID

private val darkCard = Color(0xFF1E1E1E)
private val darkSlate = Color(0xFF121212)

/**
 * Full-screen SCDL_BOX task manager.
 *
 * Displays all scheduled tasks as Dark Slate cards in a [LazyColumn].
 * Each card shows the task title, interval, type (Shell/LLM), a neon-green
 * [Switch] to toggle `isEnabled`, and icon buttons to Edit or Delete.
 *
 * A neon-green FAB at the bottom-right opens the [ScdlBoxTaskSheet] form
 * to create a new task.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScdlBoxScreen(
  db: GraphDatabase,
) {
  val context = LocalContext.current
  val dao = remember { db.scdlBoxDao() }
  val tasks by dao.observeAll().collectAsStateWithLifecycle(initialValue = emptyList())
  val scope = rememberCoroutineScope()

  var showAddSheet by remember { mutableStateOf(false) }
  var editingTask by remember { mutableStateOf<ScdlBoxEntity?>(null) }
  var confirmDeleteTask by remember { mutableStateOf<ScdlBoxEntity?>(null) }

  Scaffold(
    containerColor = absoluteBlack,
    floatingActionButton = {
      FloatingActionButton(
        onClick = { showAddSheet = true },
        containerColor = neonGreen,
        contentColor = absoluteBlack,
      ) {
        Icon(Icons.Filled.Add, contentDescription = "New scheduled task")
      }
    },
  ) { innerPadding ->
    Column(
      modifier = Modifier
        .fillMaxSize()
        .padding(innerPadding)
        .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
      // ── Header ──────────────────────────────────────────────────────
      Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Outlined.Schedule, contentDescription = null, tint = neonGreen, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Text(
          "SCDL_BOX",
          fontFamily = FontFamily.Monospace,
          fontWeight = FontWeight.Bold,
          fontSize = 18.sp,
          color = neonGreen,
        )
      }
      Text(
        "Recurring background tasks",
        fontFamily = FontFamily.Monospace,
        fontSize = 11.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 2.dp, bottom = 16.dp),
      )

      // ── Task list ─────────────────────────────────────────────────────
      if (tasks.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
          Text(
            "No tasks scheduled.\nTap + to create one.",
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
          )
        }
      } else {
        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
          items(tasks, key = { it.id }) { task ->
            ScdlTaskCard(
              task = task,
              onToggle = { enabled ->
                scope.launch {
                  dao.setEnabled(task.id, enabled)
                  ScdlBoxManager(context).onTaskUpdated(task.copy(isEnabled = enabled), wasEnabled = task.isEnabled)
                }
              },
              onEdit = { editingTask = task },
              onDelete = { confirmDeleteTask = task },
            )
          }
        }
      }
    }
  }

  // ── Add / Edit bottom sheet ──────────────────────────────────────────────
  if (showAddSheet || editingTask != null) {
    ScdlBoxTaskSheet(
      existing = editingTask,
      onDismiss = {
        showAddSheet = false
        editingTask = null
      },
      onSave = { task, ctx ->
        scope.launch {
          dao.insert(task)
          ScdlBoxManager(ctx).schedule(task)
        }
        showAddSheet = false
        editingTask = null
      },
      onUpdate = { task, ctx ->
        scope.launch {
          dao.update(task)
          ScdlBoxManager(ctx).onTaskUpdated(task)
        }
        editingTask = null
      },
    )
  }

  // ── Delete confirmation dialog ──────────────────────────────────────────
  confirmDeleteTask?.let { task ->
    AlertDialog(
      onDismissRequest = { confirmDeleteTask = null },
      containerColor = darkCard,
      title = {
        Text(
          "DELETE TASK?",
          fontFamily = FontFamily.Monospace,
          fontWeight = FontWeight.Bold,
          color = neonGreen,
        )
      },
      text = {
        Text(
          "Remove '${task.title}'?\nThis will also cancel its WorkManager schedule.",
          fontFamily = FontFamily.Monospace,
          color = Color.White,
        )
      },
      confirmButton = {
        TextButton(onClick = {
          scope.launch {
            ScdlBoxManager(context).cancel(task.id)
            dao.deleteById(task.id)
          }
          confirmDeleteTask = null
        }) {
          Text("DELETE", color = Color.Red, fontFamily = FontFamily.Monospace)
        }
      },
      dismissButton = {
        TextButton(onClick = { confirmDeleteTask = null }) {
          Text("CANCEL", color = neonGreen, fontFamily = FontFamily.Monospace)
        }
      },
    )
  }
}

// ─────────────────────────────────────────────────────────────────────────────
// Task card
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun ScdlTaskCard(
  task: ScdlBoxEntity,
  onToggle: (Boolean) -> Unit,
  onEdit: () -> Unit,
  onDelete: () -> Unit,
) {
  Column(
    modifier = Modifier
      .fillMaxWidth()
      .background(darkCard, RoundedCornerShape(8.dp))
      .border(1.dp, if (task.isEnabled) neonGreen.copy(alpha = 0.4f) else Color.DarkGray, RoundedCornerShape(8.dp))
      .padding(12.dp),
  ) {
    // ── Top row: title + enable switch ──────────────────────────────
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.Top,
    ) {
      Column(modifier = Modifier.weight(1f)) {
        Text(
          task.title,
          fontFamily = FontFamily.Monospace,
          fontWeight = FontWeight.Bold,
          fontSize = 14.sp,
          color = Color.White,
        )
        if (task.description.isNotBlank()) {
          Text(
            task.description,
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp),
          )
        }
      }
      Switch(
        checked = task.isEnabled,
        onCheckedChange = onToggle,
        colors = SwitchDefaults.colors(
          checkedThumbColor = absoluteBlack,
          checkedTrackColor = neonGreen,
          uncheckedThumbColor = Color.DarkGray,
          uncheckedTrackColor = darkSlate,
        ),
      )
    }

    // ── Metadata row ────────────────────────────────────────────────
    Spacer(Modifier.height(6.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
      MetaTag(if (task.isShellCommand) "SHELL" else "LLM")
      MetaTag("every ${task.intervalMinutes}m")
    }

    // ── Payload preview ─────────────────────────────────────────────
    if (task.payload.isNotBlank()) {
      Text(
        task.payload.take(120),
        fontFamily = FontFamily.Monospace,
        fontSize = 11.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 6.dp),
      )
    }

    // ── Action buttons: Edit + Delete ────────────────────────────────
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .padding(top = 8.dp),
      horizontalArrangement = Arrangement.End,
    ) {
      IconButton(onClick = onEdit, modifier = Modifier.size(36.dp)) {
        Icon(Icons.Filled.Edit, contentDescription = "Edit task", tint = neonGreen, modifier = Modifier.size(18.dp))
      }
      IconButton(onClick = onDelete, modifier = Modifier.size(36.dp)) {
        Icon(Icons.Filled.Delete, contentDescription = "Delete task", tint = Color.Red, modifier = Modifier.size(18.dp))
      }
    }
  }
}

@Composable
private fun MetaTag(label: String) {
  Text(
    label,
    fontFamily = FontFamily.Monospace,
    fontSize = 10.sp,
    color = absoluteBlack,
    modifier = Modifier
      .background(neonGreen.copy(alpha = 0.85f), RoundedCornerShape(3.dp))
      .padding(horizontal = 5.dp, vertical = 2.dp),
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// Add / Edit bottom sheet form
// ─────────────────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ScdlBoxTaskSheet(
  existing: ScdlBoxEntity?,
  onDismiss: () -> Unit,
  onSave: (ScdlBoxEntity, Context) -> Unit,
  onUpdate: (ScdlBoxEntity, Context) -> Unit,
) {
  val context = LocalContext.current
  val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

  var title by remember { mutableStateOf(existing?.title ?: "") }
  var description by remember { mutableStateOf(existing?.description ?: "") }
  var payload by remember { mutableStateOf(existing?.payload ?: "") }
  var isShellCommand by remember { mutableStateOf(existing?.isShellCommand ?: true) }
  var intervalMinutes by remember { mutableLongStateOf(existing?.intervalMinutes ?: 60L) }
  var titleError by remember { mutableStateOf(false) }
  var payloadError by remember { mutableStateOf(false) }

  val fieldColors = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = neonGreen,
    unfocusedBorderColor = Color.DarkGray,
    focusedLabelColor = neonGreen,
    unfocusedLabelColor = Color.Gray,
    cursorColor = neonGreen,
    focusedTextColor = Color.White,
    unfocusedTextColor = Color.White,
  )

  ModalBottomSheet(
    onDismissRequest = onDismiss,
    sheetState = sheetState,
    containerColor = darkSlate,
  ) {
    Column(
      modifier = Modifier
        .fillMaxWidth()
        .verticalScroll(rememberScrollState())
        .padding(horizontal = 20.dp, vertical = 16.dp),
      verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
      Text(
        if (existing == null) "NEW SCHEDULED TASK" else "EDIT TASK",
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Bold,
        fontSize = 15.sp,
        color = neonGreen,
      )

      OutlinedTextField(
        value = title,
        onValueChange = { title = it; titleError = false },
        label = { Text("Title *") },
        isError = titleError,
        singleLine = true,
        colors = fieldColors,
        modifier = Modifier.fillMaxWidth(),
      )

      OutlinedTextField(
        value = description,
        onValueChange = { description = it },
        label = { Text("Description (optional)") },
        colors = fieldColors,
        modifier = Modifier.fillMaxWidth(),
        maxLines = 2,
      )

      OutlinedTextField(
        value = payload,
        onValueChange = { payload = it; payloadError = false },
        label = { Text("Payload (command or prompt) *") },
        isError = payloadError,
        colors = fieldColors,
        modifier = Modifier.fillMaxWidth(),
        maxLines = 5,
      )

      // ── Type toggle ───────────────────────────────────────────────
      Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
      ) {
        Column {
          Text("Payload type", fontFamily = FontFamily.Monospace, fontSize = 13.sp, color = Color.White)
          Text(
            if (isShellCommand) "Shell command (PRoot)" else "LLM natural-language prompt",
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
        Switch(
          checked = isShellCommand,
          onCheckedChange = { isShellCommand = it },
          colors = SwitchDefaults.colors(
            checkedThumbColor = absoluteBlack,
            checkedTrackColor = neonGreen,
          ),
        )
      }

      // ── Interval ──────────────────────────────────────────────────
      OutlinedTextField(
        value = intervalMinutes.toString(),
        onValueChange = { intervalMinutes = it.toLongOrNull()?.coerceAtLeast(15L) ?: intervalMinutes },
        label = { Text("Repeat interval (minutes, min 15)") },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        singleLine = true,
        colors = fieldColors,
        modifier = Modifier.fillMaxWidth(),
      )

      // ── Save button ───────────────────────────────────────────────
      Button(
        onClick = {
          titleError = title.isBlank()
          payloadError = payload.isBlank()
          if (titleError || payloadError) return@Button

          val entity = (existing ?: ScdlBoxEntity(
            id = UUID.randomUUID().toString(),
            title = title.trim(),
            description = description.trim(),
            payload = payload.trim(),
            isShellCommand = isShellCommand,
            intervalMinutes = intervalMinutes.coerceAtLeast(15L),
            isEnabled = true,
          )).let { base ->
            if (existing != null) base.copy(
              title = title.trim(),
              description = description.trim(),
              payload = payload.trim(),
              isShellCommand = isShellCommand,
              intervalMinutes = intervalMinutes.coerceAtLeast(15L),
            ) else base
          }

          if (existing == null) onSave(entity, context) else onUpdate(entity, context)
        },
        colors = ButtonDefaults.buttonColors(containerColor = neonGreen, contentColor = absoluteBlack),
        modifier = Modifier.fillMaxWidth(),
      ) {
        Text(if (existing == null) "SCHEDULE" else "UPDATE", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
      }

      Spacer(Modifier.height(16.dp))
    }
  }
}
