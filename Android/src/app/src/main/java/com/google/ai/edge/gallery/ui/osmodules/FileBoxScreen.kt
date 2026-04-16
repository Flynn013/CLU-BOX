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

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.ai.edge.gallery.data.FileBoxManager
import com.google.ai.edge.gallery.data.FileNode
import com.google.ai.edge.gallery.data.exportDirectoryAsZip
import com.google.ai.edge.gallery.ui.theme.absoluteBlack
import com.google.ai.edge.gallery.ui.theme.neonGreen
import java.io.File

/**
 * FILE_BOX — sandboxed code workspace for AI-generated projects.
 *
 * Shows a hierarchical file tree. Tapping a file opens it in an editable text field.
 * A top-level "Export Project" button zips a root directory to Downloads.
 */
@Composable
fun FileBoxScreen(fileBoxManager: FileBoxManager) {
  val context = LocalContext.current
  // Bump to force recomposition after file writes/deletes.
  var refreshKey by remember { mutableIntStateOf(0) }
  // Inotify bridge: collect the FileObserver revision counter so that
  // terminal-originated file changes also refresh the tree automatically.
  val fsRevision by fileBoxManager.revision.collectAsState()
  var selectedFilePath by remember { mutableStateOf<String?>(null) }
  var editorContent by remember { mutableStateOf("") }
  var editorDirty by remember { mutableStateOf(false) }
  var showNewFileDialog by remember { mutableStateOf(false) }
  var showDeleteConfirm by remember { mutableStateOf(false) }

  // Rebuild the tree whenever refreshKey changes OR the FileObserver detects changes.
  val fileTree = remember(refreshKey, fsRevision) { fileBoxManager.getFileTree() }

  // Load file content when selection changes.
  LaunchedEffect(selectedFilePath) {
    val path = selectedFilePath
    if (path != null) {
      editorContent = fileBoxManager.readCodeFile(path) ?: ""
      editorDirty = false
    }
  }

  Column(
    modifier = Modifier
      .fillMaxSize()
      .background(absoluteBlack)
      .padding(horizontal = 8.dp),
  ) {
    // ── Toolbar ──────────────────────────────────────────────
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .horizontalScroll(rememberScrollState())
        .padding(vertical = 4.dp),
      horizontalArrangement = Arrangement.spacedBy(6.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      // New file
      OutlinedButton(
        onClick = { showNewFileDialog = true },
        colors = ButtonDefaults.outlinedButtonColors(contentColor = neonGreen),
      ) {
        Icon(Icons.Default.CreateNewFolder, contentDescription = null, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(4.dp))
        Text("New File", fontFamily = FontFamily.Monospace, fontSize = 12.sp)
      }

      // Save (only when editing)
      if (selectedFilePath != null && editorDirty) {
        OutlinedButton(
          onClick = {
            selectedFilePath?.let { path ->
              fileBoxManager.writeCodeFile(path, editorContent)
              editorDirty = false
              refreshKey++
              Toast.makeText(context, "Saved: $path", Toast.LENGTH_SHORT).show()
            }
          },
          colors = ButtonDefaults.outlinedButtonColors(contentColor = neonGreen),
        ) {
          Icon(Icons.Default.Save, contentDescription = null, modifier = Modifier.size(16.dp))
          Spacer(Modifier.width(4.dp))
          Text("Save", fontFamily = FontFamily.Monospace, fontSize = 12.sp)
        }
      }

      // Delete selected
      if (selectedFilePath != null) {
        OutlinedButton(
          onClick = { showDeleteConfirm = true },
          colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.Red),
        ) {
          Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(16.dp))
          Spacer(Modifier.width(4.dp))
          Text("Delete", fontFamily = FontFamily.Monospace, fontSize = 12.sp)
        }
      }

      // Export project as zip
      OutlinedButton(
        onClick = {
          val timestamp = System.currentTimeMillis()
          val name = exportDirectoryAsZip(context, fileBoxManager.root, "clu_file_box_$timestamp.zip")
          if (name != null) {
            Toast.makeText(context, "Exported to Downloads: $name", Toast.LENGTH_LONG).show()
          } else {
            Toast.makeText(context, "Export failed or directory is empty", Toast.LENGTH_SHORT).show()
          }
        },
        colors = ButtonDefaults.outlinedButtonColors(contentColor = neonGreen),
      ) {
        Icon(Icons.Default.FileDownload, contentDescription = null, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(4.dp))
        Text("Export ZIP", fontFamily = FontFamily.Monospace, fontSize = 12.sp)
      }
    }

    Spacer(Modifier.height(4.dp))

    // ── Main area: tree + editor ─────────────────────────────
    Row(modifier = Modifier.fillMaxSize()) {
      // File tree (left panel)
      Column(
        modifier = Modifier
          .width(160.dp)
          .fillMaxSize()
          .verticalScroll(rememberScrollState())
          .padding(end = 4.dp),
      ) {
        if (fileTree.children.isEmpty()) {
          Text(
            "Empty workspace.\nUse 'New File' or let\nthe AI create files.",
            color = neonGreen.copy(alpha = 0.5f),
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            modifier = Modifier.padding(8.dp),
          )
        } else {
          for (child in fileTree.children) {
            FileTreeNode(
              node = child,
              depth = 0,
              selectedPath = selectedFilePath,
              onFileClicked = { path -> selectedFilePath = path },
            )
          }
        }
      }

      // Editor (right panel) — syntax-highlighted code editor
      Box(
        modifier = Modifier
          .fillMaxSize()
          .background(Color(0xFF0A0A0A), RoundedCornerShape(4.dp))
          .padding(4.dp),
      ) {
        if (selectedFilePath != null) {
          val ext = remember(selectedFilePath) {
            selectedFilePath?.substringAfterLast('.', "") ?: ""
          }
          Column(modifier = Modifier.fillMaxSize()) {
            Text(
              selectedFilePath ?: "",
              color = neonGreen.copy(alpha = 0.7f),
              fontFamily = FontFamily.Monospace,
              fontSize = 10.sp,
              modifier = Modifier.padding(bottom = 4.dp),
            )
            // Syntax-highlighting visual transformation for BasicTextField.
            val syntaxTransformation = remember(ext) {
              VisualTransformation { text ->
                val highlighted = highlightSyntax(text.text, ext)
                TransformedText(highlighted, OffsetMapping.Identity)
              }
            }
            BasicTextField(
              value = editorContent,
              onValueChange = {
                editorContent = it
                editorDirty = true
              },
              modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .horizontalScroll(rememberScrollState())
                .padding(4.dp),
              textStyle = TextStyle(
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                color = Color(0xFFF8F8F2),
              ),
              cursorBrush = SolidColor(neonGreen),
              visualTransformation = syntaxTransformation,
            )
          }
        } else {
          Text(
            "← Select a file to view/edit",
            color = neonGreen.copy(alpha = 0.4f),
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            modifier = Modifier.align(Alignment.Center),
          )
        }
      }
    }
  }

  // ── New File Dialog ────────────────────────────────────────
  if (showNewFileDialog) {
    var newPath by remember { mutableStateOf("") }
    AlertDialog(
      onDismissRequest = { showNewFileDialog = false },
      title = {
        Text("NEW FILE", fontFamily = FontFamily.Monospace, color = neonGreen)
      },
      text = {
        Column {
          Text(
            "Enter path relative to workspace root.\nNested folders are auto-created.",
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            color = Color.Gray,
          )
          Spacer(Modifier.height(8.dp))
          OutlinedTextField(
            value = newPath,
            onValueChange = { newPath = it },
            placeholder = { Text("e.g. myProject/src/main.kt") },
            singleLine = true,
            textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp),
          )
        }
      },
      confirmButton = {
        TextButton(onClick = {
          val trimmed = newPath.trim()
          if (trimmed.isNotEmpty()) {
            if (fileBoxManager.isAllowedExtension(trimmed)) {
              fileBoxManager.writeCodeFile(trimmed, "")
              selectedFilePath = trimmed
              refreshKey++
            } else {
              Toast.makeText(context, "Extension not allowed. Text/code files only.", Toast.LENGTH_SHORT).show()
            }
          }
          showNewFileDialog = false
        }) {
          Text("CREATE", color = neonGreen, fontFamily = FontFamily.Monospace)
        }
      },
      dismissButton = {
        TextButton(onClick = { showNewFileDialog = false }) {
          Text("CANCEL", fontFamily = FontFamily.Monospace)
        }
      },
    )
  }

  // ── Delete confirmation ────────────────────────────────────
  if (showDeleteConfirm) {
    AlertDialog(
      onDismissRequest = { showDeleteConfirm = false },
      title = { Text("DELETE FILE?", fontFamily = FontFamily.Monospace, color = Color.Red) },
      text = {
        Text(
          "Delete '${selectedFilePath}'? This cannot be undone.",
          fontFamily = FontFamily.Monospace,
          fontSize = 12.sp,
        )
      },
      confirmButton = {
        TextButton(onClick = {
          selectedFilePath?.let { fileBoxManager.deleteFile(it) }
          selectedFilePath = null
          editorContent = ""
          editorDirty = false
          refreshKey++
          showDeleteConfirm = false
        }) {
          Text("DELETE", color = Color.Red, fontFamily = FontFamily.Monospace)
        }
      },
      dismissButton = {
        TextButton(onClick = { showDeleteConfirm = false }) {
          Text("CANCEL", fontFamily = FontFamily.Monospace)
        }
      },
    )
  }
}

// ── File tree node composable ────────────────────────────────────

@Composable
private fun FileTreeNode(
  node: FileNode,
  depth: Int,
  selectedPath: String?,
  onFileClicked: (String) -> Unit,
) {
  val indent = 12.dp * depth
  var expanded by remember { mutableStateOf(depth < 2) }

  if (node.isDirectory) {
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .clickable { expanded = !expanded }
        .padding(start = indent, top = 2.dp, bottom = 2.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Icon(
        if (expanded) Icons.Default.FolderOpen else Icons.Default.Folder,
        contentDescription = null,
        tint = neonGreen.copy(alpha = 0.7f),
        modifier = Modifier.size(14.dp),
      )
      Spacer(Modifier.width(4.dp))
      Text(
        node.name,
        fontFamily = FontFamily.Monospace,
        fontSize = 11.sp,
        color = neonGreen.copy(alpha = 0.8f),
      )
    }
    if (expanded) {
      for (child in node.children) {
        FileTreeNode(child, depth + 1, selectedPath, onFileClicked)
      }
    }
  } else {
    val isSelected = node.relativePath == selectedPath
    val bgColor = if (isSelected) neonGreen.copy(alpha = 0.15f) else Color.Transparent
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .background(bgColor)
        .clickable { onFileClicked(node.relativePath) }
        .padding(start = indent, top = 2.dp, bottom = 2.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Icon(
        Icons.AutoMirrored.Filled.InsertDriveFile,
        contentDescription = null,
        tint = if (isSelected) neonGreen else Color.Gray,
        modifier = Modifier.size(14.dp),
      )
      Spacer(Modifier.width(4.dp))
      Text(
        node.name,
        fontFamily = FontFamily.Monospace,
        fontSize = 11.sp,
        color = if (isSelected) neonGreen else Color(0xFFAAAAAA),
      )
    }
  }
}
