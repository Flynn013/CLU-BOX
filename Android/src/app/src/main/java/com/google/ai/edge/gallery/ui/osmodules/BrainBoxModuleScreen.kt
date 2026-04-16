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

import android.content.ContentValues
import android.os.Build
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.google.ai.edge.gallery.data.brainbox.BrainBoxDao
import com.google.ai.edge.gallery.data.brainbox.NeuronEntity
import com.google.ai.edge.gallery.data.brainbox.exportBrain
import com.google.ai.edge.gallery.data.brainbox.exportBrainToMarkdown
import com.google.ai.edge.gallery.data.brainbox.importBrain
import com.google.ai.edge.gallery.data.brainbox.importBrainFromMarkdown
import com.google.ai.edge.gallery.data.brainbox.saveBrainMarkdownToDownloads
import com.google.ai.edge.gallery.ui.theme.absoluteBlack
import com.google.ai.edge.gallery.ui.theme.neonGreen
import java.util.UUID
import kotlinx.coroutines.launch

/**
 * BRAIN_BOX module — view, create, edit, and delete Neurons with Upload/Download Brain I/O.
 */
@Composable
fun BrainBoxModuleScreen(dao: BrainBoxDao) {
  val scope = rememberCoroutineScope()
  val context = LocalContext.current
  val neurons = remember { mutableStateListOf<NeuronEntity>() }

  var showAddDialog by remember { mutableStateOf(false) }
  var editTarget by remember { mutableStateOf<NeuronEntity?>(null) }
  var showDeleteDialog by remember { mutableStateOf<NeuronEntity?>(null) }

  // Upload (import) state — legacy JSON
  var showImportDialog by remember { mutableStateOf(false) }
  var importJson by remember { mutableStateOf("") }
  var importError by remember { mutableStateOf("") }

  // Markdown file picker launcher (Phase 3).
  val mdImportLauncher = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.OpenDocument(),
  ) { uri ->
    if (uri != null) {
      scope.launch {
        try {
          val count = importBrainFromMarkdown(context, dao, uri)
          neurons.clear()
          neurons.addAll(dao.getAllNeurons())
          Toast.makeText(context, "Imported $count neuron(s) from Markdown", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
          Toast.makeText(context, "Import failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
      }
    }
  }

  // Load all neurons on entry.
  LaunchedEffect(Unit) {
    neurons.clear()
    neurons.addAll(dao.getAllNeurons())
  }

  // ---- Dialogs ----

  if (showAddDialog || editTarget != null) {
    val existing = editTarget
    var label by remember(existing) { mutableStateOf(existing?.label ?: "") }
    var type by remember(existing) { mutableStateOf(existing?.type ?: "") }
    var content by remember(existing) { mutableStateOf(existing?.content ?: "") }
    var synapses by remember(existing) { mutableStateOf(existing?.synapses ?: "") }
    var isCore by remember(existing) { mutableStateOf(existing?.isCore ?: false) }
    AlertDialog(
      onDismissRequest = { showAddDialog = false; editTarget = null },
      title = {
        Text(
          if (existing == null) "NEW NEURON" else "EDIT NEURON",
          fontFamily = FontFamily.Monospace,
          color = neonGreen,
        )
      },
      text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
          NeonTextField(value = label, onValueChange = { label = it }, label = "Label")
          NeonTextField(value = type, onValueChange = { type = it }, label = "Type (Concept/Code/Session_Log/Lore)")
          NeonTextField(value = content, onValueChange = { content = it }, label = "Content (markdown)", singleLine = false)
          NeonTextField(value = synapses, onValueChange = { synapses = it }, label = "Synapses ([[Wiki-Links]])", singleLine = false)
          Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
          ) {
            Icon(
              if (isCore) Icons.Outlined.Lock else Icons.Outlined.LockOpen,
              contentDescription = null,
              tint = if (isCore) neonGreen else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
              "CORE MEMORY",
              fontFamily = FontFamily.Monospace,
              color = if (isCore) neonGreen else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Switch(
              checked = isCore,
              onCheckedChange = { isCore = it },
              colors = SwitchDefaults.colors(
                checkedThumbColor = absoluteBlack,
                checkedTrackColor = neonGreen,
              ),
            )
          }
        }
      },
      confirmButton = {
        TextButton(onClick = {
          scope.launch {
            val neuron = NeuronEntity(
              id = existing?.id ?: UUID.randomUUID().toString(),
              label = label.trim(),
              type = type.trim(),
              content = content.trim(),
              synapses = synapses.trim(),
              isCore = isCore,
            )
            dao.insertNeuron(neuron)
            neurons.clear()
            neurons.addAll(dao.getAllNeurons())
          }
          showAddDialog = false; editTarget = null
        }) { Text("SAVE", color = neonGreen, fontFamily = FontFamily.Monospace) }
      },
      dismissButton = {
        TextButton(onClick = { showAddDialog = false; editTarget = null }) {
          Text("CANCEL", fontFamily = FontFamily.Monospace)
        }
      },
    )
  }

  showDeleteDialog?.let { target ->
    AlertDialog(
      onDismissRequest = { showDeleteDialog = null },
      title = { Text("DELETE NEURON?", fontFamily = FontFamily.Monospace, color = neonGreen) },
      text = { Text("\"${target.label}\" will be permanently removed.", fontFamily = FontFamily.Monospace) },
      confirmButton = {
        TextButton(onClick = {
          scope.launch {
            dao.deleteNeuron(target)
            neurons.remove(target)
          }
          showDeleteDialog = null
        }) { Text("DELETE", color = MaterialTheme.colorScheme.error, fontFamily = FontFamily.Monospace) }
      },
      dismissButton = {
        TextButton(onClick = { showDeleteDialog = null }) {
          Text("CANCEL", fontFamily = FontFamily.Monospace)
        }
      },
    )
  }

  if (showImportDialog) {
    AlertDialog(
      onDismissRequest = { showImportDialog = false; importError = "" },
      title = { Text("UPLOAD BRAIN", fontFamily = FontFamily.Monospace, color = neonGreen) },
      text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
          Text("Paste exported JSON to overwrite the knowledge graph:", fontFamily = FontFamily.Monospace)
          NeonTextField(value = importJson, onValueChange = { importJson = it }, label = "JSON", singleLine = false)
          if (importError.isNotEmpty()) {
            Text(importError, color = MaterialTheme.colorScheme.error, fontFamily = FontFamily.Monospace)
          }
        }
      },
      confirmButton = {
        TextButton(onClick = {
          scope.launch {
            try {
              importBrain(dao, importJson)
              neurons.clear()
              neurons.addAll(dao.getAllNeurons())
              showImportDialog = false
              importJson = ""
              importError = ""
            } catch (e: Exception) {
              importError = "Parse error: ${e.message}"
            }
          }
        }) { Text("IMPORT", color = neonGreen, fontFamily = FontFamily.Monospace) }
      },
      dismissButton = {
        TextButton(onClick = { showImportDialog = false; importError = "" }) {
          Text("CANCEL", fontFamily = FontFamily.Monospace)
        }
      },
    )
  }

  // ---- Main UI ----

  Scaffold(
    containerColor = absoluteBlack,
    floatingActionButton = {
      FloatingActionButton(
        onClick = { showAddDialog = true },
        containerColor = neonGreen,
        contentColor = absoluteBlack,
      ) {
        Icon(Icons.Outlined.Add, contentDescription = "Add Neuron")
      }
    },
  ) { innerPadding ->
    Column(
      modifier = Modifier.fillMaxSize().padding(innerPadding).padding(horizontal = 16.dp),
    ) {
      Spacer(Modifier.height(12.dp))
      Text(
        "BRAIN_BOX",
        style = MaterialTheme.typography.headlineSmall,
        color = neonGreen,
        fontFamily = FontFamily.Monospace,
      )
      Text(
        "[ KNOWLEDGE GRAPH MANAGER ]",
        style = MaterialTheme.typography.labelSmall,
        color = neonGreen,
        fontFamily = FontFamily.Monospace,
      )
      Spacer(Modifier.height(12.dp))

      // Row 1: Legacy JSON I/O
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(
          onClick = {
            scope.launch {
              val json = exportBrain(dao)
              val timestamp = System.currentTimeMillis()
              val fileName = "brainbox_$timestamp.json"
              val resolver = context.contentResolver
              val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                put(MediaStore.Downloads.MIME_TYPE, "application/json")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                  put(MediaStore.Downloads.IS_PENDING, 1)
                }
              }
              val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
              if (uri != null) {
                val stream = resolver.openOutputStream(uri)
                if (stream != null) {
                  stream.use { it.write(json.toByteArray(Charsets.UTF_8)) }
                  if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    values.clear()
                    values.put(MediaStore.Downloads.IS_PENDING, 0)
                    resolver.update(uri, values, null, null)
                  }
                  Toast.makeText(context, "Saved to Downloads/$fileName", Toast.LENGTH_LONG).show()
                } else {
                  Toast.makeText(context, "Export failed — could not open file for writing", Toast.LENGTH_LONG).show()
                }
              } else {
                Toast.makeText(context, "Export failed — storage unavailable", Toast.LENGTH_LONG).show()
              }
            }
          },
          colors = ButtonDefaults.outlinedButtonColors(contentColor = neonGreen),
        ) { Text("↓ JSON", fontFamily = FontFamily.Monospace) }
        OutlinedButton(
          onClick = { showImportDialog = true },
          colors = ButtonDefaults.outlinedButtonColors(contentColor = neonGreen),
        ) { Text("↑ JSON", fontFamily = FontFamily.Monospace) }
      }

      Spacer(Modifier.height(4.dp))

      // Row 2: Markdown Review I/O (Phase 2 & 3)
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(
          onClick = {
            scope.launch {
              val md = exportBrainToMarkdown(dao, includeCore = true)
              val ok = saveBrainMarkdownToDownloads(context, md)
              if (ok) {
                Toast.makeText(context, "Saved CLU_BRAIN_REVIEW.md to Downloads", Toast.LENGTH_LONG).show()
              } else {
                Toast.makeText(context, "Markdown export failed", Toast.LENGTH_LONG).show()
              }
            }
          },
          colors = ButtonDefaults.outlinedButtonColors(contentColor = neonGreen),
        ) { Text("↓ REVIEW MD", fontFamily = FontFamily.Monospace) }
        OutlinedButton(
          onClick = { mdImportLauncher.launch(arrayOf("text/*")) },
          colors = ButtonDefaults.outlinedButtonColors(contentColor = neonGreen),
        ) { Text("↑ FLASH BRAIN", fontFamily = FontFamily.Monospace) }
      }

      Spacer(Modifier.height(12.dp))
      if (neurons.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
          Text("No neurons. Tap + to forge one.", fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
      } else {
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
          items(neurons, key = { it.id }) { neuron ->
            NeuronCard(
              neuron = neuron,
              onEdit = { editTarget = neuron },
              onDelete = { showDeleteDialog = neuron },
              onToggleCore = {
                scope.launch {
                  val updated = neuron.copy(isCore = !neuron.isCore)
                  dao.updateNeuron(updated)
                  val idx = neurons.indexOfFirst { it.id == neuron.id }
                  if (idx >= 0) neurons[idx] = updated
                }
              },
            )
          }
        }
      }
    }
  }
}

@Composable
private fun NeuronCard(
  neuron: NeuronEntity,
  onEdit: () -> Unit,
  onDelete: () -> Unit,
  onToggleCore: () -> Unit,
) {
  Card(
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    modifier = Modifier.fillMaxWidth().border(
      width = 1.dp,
      color = if (neuron.isCore) neonGreen else neonGreen.copy(alpha = 0.4f),
      shape = RoundedCornerShape(4.dp),
    ),
  ) {
    Row(
      modifier = Modifier.padding(12.dp),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.Top,
    ) {
      Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
          if (neuron.isCore) {
            Icon(Icons.Outlined.Lock, contentDescription = "Core", tint = neonGreen, modifier = Modifier.padding(end = 2.dp))
          }
          Text(neuron.label, color = neonGreen, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.titleSmall)
        }
        Text("[${neuron.type}]", color = MaterialTheme.colorScheme.onSurfaceVariant, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.labelSmall)
        Text(neuron.content, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, maxLines = 3)
        if (neuron.synapses.isNotBlank()) {
          Text("⚡ ${neuron.synapses}", color = neonGreen.copy(alpha = 0.7f), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.labelSmall, maxLines = 1)
        }
      }
      Row {
        IconButton(onClick = onToggleCore) {
          Icon(
            if (neuron.isCore) Icons.Outlined.Lock else Icons.Outlined.LockOpen,
            contentDescription = if (neuron.isCore) "Unlock (make malleable)" else "Lock (make core)",
            tint = if (neuron.isCore) neonGreen else MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
        IconButton(onClick = onEdit) { Icon(Icons.Outlined.Edit, contentDescription = "Edit", tint = neonGreen) }
        IconButton(onClick = onDelete) { Icon(Icons.Outlined.Delete, contentDescription = "Delete", tint = MaterialTheme.colorScheme.error) }
      }
    }
  }
}
