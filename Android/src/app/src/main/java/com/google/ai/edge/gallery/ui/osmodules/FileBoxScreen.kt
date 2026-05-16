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
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import com.google.ai.edge.gallery.data.SharedShellManager
import com.google.ai.edge.gallery.data.exportDirectoryAsZip
import com.google.ai.edge.gallery.ui.theme.absoluteBlack
import com.google.ai.edge.gallery.ui.theme.neonGreen
import com.google.ai.edge.gallery.ui.theme.terminalLightGrey
import com.google.ai.edge.gallery.ui.theme.terminalMidGrey
import com.google.ai.edge.gallery.ui.theme.terminalOutline
import java.io.File

/**
 * FILE_BOX — desktop-level sandboxed code workspace for AI-generated projects.
 *
 * Features:
 * - Two-panel desktop layout: collapsible file tree + live editor
 * - Full-screen editing overlay when a file is tapped in the tree
 * - Syntax highlighting, breadcrumb path bar, per-file save status
 * - Toolbar: New File, Save, Delete, Export ZIP, ▶ TERM
 * - Auto-refresh when the [FileBoxManager.revision] counter advances (via inotify)
 *
 * @param sharedShellManager When non-null, a ▶ TERM button lets the user cd to
 *   the selected file's directory in the live MSTR_CTRL terminal session.
 */
@Composable
fun FileBoxScreen(
  fileBoxManager: FileBoxManager,
  sharedShellManager: SharedShellManager? = null,
) {
  val context = LocalContext.current
  var refreshKey by remember { mutableIntStateOf(0) }
  val fsRevision by fileBoxManager.revision.collectAsState()

  var selectedFilePath by remember { mutableStateOf<String?>(null) }
  var editorContent by remember { mutableStateOf("") }
  var editorDirty by remember { mutableStateOf(false) }

  // When true, the full-screen editor overlay covers the entire module.
  var fullScreenEditing by remember { mutableStateOf(false) }

  var showNewFileDialog by remember { mutableStateOf(false) }
  var showDeleteConfirm by remember { mutableStateOf(false) }
  var showRenameDialog by remember { mutableStateOf(false) }
  var renameTarget by remember { mutableStateOf<String?>(null) }

  val fileTree = remember(refreshKey, fsRevision) { fileBoxManager.getFileTree() }

  LaunchedEffect(selectedFilePath) {
    val path = selectedFilePath
    fileBoxManager.setCurrentFile(path)
    if (path != null) {
      editorContent = fileBoxManager.readCodeFile(path) ?: ""
      editorDirty = false
      fileBoxManager.setCursorLine(1)
    }
  }

  Box(modifier = Modifier.fillMaxSize().background(absoluteBlack)) {
    // ── Main two-panel layout ──────────────────────────────────────────
    Column(modifier = Modifier.fillMaxSize()) {
      // ── Breadcrumb / path bar ──────────────────────────────────────
      Row(
        modifier = Modifier
          .fillMaxWidth()
          .background(terminalMidGrey)
          .padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
      ) {
        Text(
          "FILE_BOX",
          color = neonGreen,
          fontFamily = FontFamily.Monospace,
          fontSize = 12.sp,
        )
        Text("▸", color = neonGreen.copy(alpha = 0.5f), fontFamily = FontFamily.Monospace, fontSize = 12.sp)
        Text(
          selectedFilePath ?: "workspace",
          color = if (selectedFilePath != null) Color.White else Color.White.copy(alpha = 0.5f),
          fontFamily = FontFamily.Monospace,
          fontSize = 12.sp,
          modifier = Modifier.weight(1f),
        )
        if (selectedFilePath != null) {
          IconButton(onClick = { fullScreenEditing = true }, modifier = Modifier.size(28.dp)) {
            Icon(Icons.Default.FullscreenExit, contentDescription = "Full screen",
              tint = neonGreen.copy(alpha = 0.8f), modifier = Modifier.size(18.dp))
          }
        }
      }

      // ── Toolbar ────────────────────────────────────────────────────
      Row(
        modifier = Modifier
          .fillMaxWidth()
          .horizontalScroll(rememberScrollState())
          .background(terminalMidGrey)
          .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        ToolbarButton(label = "＋ File", icon = { Icon(Icons.Default.CreateNewFolder, null, Modifier.size(14.dp)) }) {
          showNewFileDialog = true
        }
        if (selectedFilePath != null && editorDirty) {
          ToolbarButton(label = "Save", icon = { Icon(Icons.Default.Save, null, Modifier.size(14.dp)) }, highlight = true) {
            selectedFilePath?.let { path ->
              fileBoxManager.writeCodeFile(path, editorContent)
              editorDirty = false
              refreshKey++
              Toast.makeText(context, "Saved", Toast.LENGTH_SHORT).show()
            }
          }
        }
        if (selectedFilePath != null) {
          ToolbarButton(label = "Rename", icon = {
            Text("✎", color = neonGreen, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
          }) {
            renameTarget = selectedFilePath
            showRenameDialog = true
          }
          ToolbarButton(label = "Delete", icon = { Icon(Icons.Default.Delete, null, Modifier.size(14.dp)) }, danger = true) {
            showDeleteConfirm = true
          }
          sharedShellManager?.let { mgr ->
            val parentRelative = File(selectedFilePath ?: ".").parent ?: "."
            ToolbarButton(label = "▶ TERM", icon = { Icon(Icons.Default.Terminal, null, Modifier.size(14.dp)) }) {
              val absParent = File(fileBoxManager.root, parentRelative).absolutePath
              mgr.injectCommand("cd \"$absParent\" && ls -la")
              Toast.makeText(context, "cd $parentRelative → MSTR_CTRL", Toast.LENGTH_SHORT).show()
            }
          }
        }
        ToolbarButton(label = "Export", icon = { Icon(Icons.Default.FileDownload, null, Modifier.size(14.dp)) }) {
          val ts = System.currentTimeMillis()
          val name = exportDirectoryAsZip(context, fileBoxManager.root, "clu_file_box_$ts.zip")
          if (name != null) Toast.makeText(context, "Exported: $name", Toast.LENGTH_LONG).show()
          else Toast.makeText(context, "Export failed or empty", Toast.LENGTH_SHORT).show()
        }
      }

      // ── Two-panel body ─────────────────────────────────────────────
      Row(modifier = Modifier.fillMaxSize()) {
        // File tree (left panel, 160dp wide)
        Column(
          modifier = Modifier
            .width(160.dp)
            .fillMaxSize()
            .border(width = 1.dp, color = terminalOutline, shape = RoundedCornerShape(0.dp))
            .verticalScroll(rememberScrollState())
            .padding(end = 4.dp, top = 4.dp),
        ) {
          if (fileTree.children.isEmpty()) {
            Text(
              "Empty workspace.\n\nUse '＋ File' or let\nCLU create files.",
              color = Color.White.copy(alpha = 0.55f),
              fontFamily = FontFamily.Monospace,
              fontSize = 12.sp,
              modifier = Modifier.padding(8.dp),
            )
          } else {
            for (child in fileTree.children) {
              FileTreeNode(
                node = child,
                depth = 0,
                selectedPath = selectedFilePath,
                onFileClicked = { path ->
                  selectedFilePath = path
                  fullScreenEditing = true
                },
              )
            }
          }
        }

        // Editor (right panel — occupies remaining width)
        Box(
          modifier = Modifier
            .fillMaxSize()
            .background(absoluteBlack)
            .padding(4.dp),
        ) {
          if (selectedFilePath != null) {
            val ext = remember(selectedFilePath) {
              selectedFilePath?.substringAfterLast('.', "") ?: ""
            }
            Column(modifier = Modifier.fillMaxSize()) {
              // Dirty indicator row
              if (editorDirty) {
                Row(
                  modifier = Modifier
                    .fillMaxWidth()
                    .background(neonGreen.copy(alpha = 0.08f))
                    .padding(horizontal = 8.dp, vertical = 2.dp),
                  verticalAlignment = Alignment.CenterVertically,
                  horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                  Text("●", color = neonGreen, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                  Text(
                    "Unsaved changes",
                    color = neonGreen.copy(alpha = 0.8f),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    modifier = Modifier.weight(1f),
                  )
                  Text(
                    "Tap Save or open full-screen",
                    color = Color.White.copy(alpha = 0.4f),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                  )
                }
              }
              val syntaxTransformation = remember(ext) {
                VisualTransformation { text ->
                  val highlighted = highlightSyntax(text.text, ext)
                  TransformedText(highlighted, OffsetMapping.Identity)
                }
              }
              BasicTextField(
                value = editorContent,
                onValueChange = { editorContent = it; editorDirty = true },
                modifier = Modifier
                  .fillMaxSize()
                  .verticalScroll(rememberScrollState())
                  .horizontalScroll(rememberScrollState())
                  .padding(4.dp),
                textStyle = TextStyle(
                  fontFamily = FontFamily.Monospace,
                  fontSize = 14.sp,
                  color = Color.White,
                ),
                cursorBrush = SolidColor(neonGreen),
                visualTransformation = syntaxTransformation,
              )
            }
          } else {
            Column(
              modifier = Modifier.align(Alignment.Center),
              horizontalAlignment = Alignment.CenterHorizontally,
              verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
              Text("FILE_BOX", color = neonGreen, fontFamily = FontFamily.Monospace, fontSize = 18.sp)
              Text(
                "← Select a file to edit",
                color = Color.White.copy(alpha = 0.5f),
                fontFamily = FontFamily.Monospace,
                fontSize = 13.sp,
              )
            }
          }
        }
      }
    }

    // ── Full-Screen Editor Overlay ─────────────────────────────────────
    AnimatedVisibility(
      visible = fullScreenEditing && selectedFilePath != null,
      enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
      exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
    ) {
      Box(
        modifier = Modifier
          .fillMaxSize()
          .background(absoluteBlack),
      ) {
        Column(modifier = Modifier.fillMaxSize()) {
          // Full-screen header bar
          Row(
            modifier = Modifier
              .fillMaxWidth()
              .background(terminalMidGrey)
              .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
          ) {
            IconButton(onClick = { fullScreenEditing = false }, modifier = Modifier.size(32.dp)) {
              Icon(Icons.Default.Close, contentDescription = "Exit full screen",
                tint = neonGreen, modifier = Modifier.size(20.dp))
            }
            Text(
              selectedFilePath ?: "",
              color = Color.White,
              fontFamily = FontFamily.Monospace,
              fontSize = 12.sp,
              modifier = Modifier.weight(1f),
            )
            if (editorDirty) {
              TextButton(
                onClick = {
                  selectedFilePath?.let { path ->
                    fileBoxManager.writeCodeFile(path, editorContent)
                    editorDirty = false
                    refreshKey++
                    Toast.makeText(context, "Saved", Toast.LENGTH_SHORT).show()
                  }
                },
              ) {
                Text("SAVE", color = neonGreen, fontFamily = FontFamily.Monospace, fontSize = 13.sp)
              }
            } else {
              Text("●", color = Color.White.copy(alpha = 0.3f), fontFamily = FontFamily.Monospace, fontSize = 18.sp)
            }
          }
          // Full editor area
          val ext = remember(selectedFilePath) {
            selectedFilePath?.substringAfterLast('.', "") ?: ""
          }
          val syntaxTransformation = remember(ext) {
            VisualTransformation { text ->
              val highlighted = highlightSyntax(text.text, ext)
              TransformedText(highlighted, OffsetMapping.Identity)
            }
          }
          BasicTextField(
            value = editorContent,
            onValueChange = { editorContent = it; editorDirty = true },
            modifier = Modifier
              .fillMaxSize()
              .verticalScroll(rememberScrollState())
              .horizontalScroll(rememberScrollState())
              .padding(8.dp),
            textStyle = TextStyle(
              fontFamily = FontFamily.Monospace,
              fontSize = 15.sp,
              color = Color.White,
              lineHeight = 22.sp,
            ),
            cursorBrush = SolidColor(neonGreen),
            visualTransformation = syntaxTransformation,
          )
        }
      }
    }
  }

  // ── New File Dialog ──────────────────────────────────────────────────
  if (showNewFileDialog) {
    var newPath by remember { mutableStateOf("") }
    AlertDialog(
      onDismissRequest = { showNewFileDialog = false },
      title = { Text("NEW FILE", fontFamily = FontFamily.Monospace, color = Color.White) },
      text = {
        Column {
          Text(
            "Path relative to workspace root.\nNested folders auto-created.",
            fontFamily = FontFamily.Monospace, fontSize = 13.sp,
            color = Color.White.copy(alpha = 0.6f),
          )
          Spacer(Modifier.height(8.dp))
          OutlinedTextField(
            value = newPath, onValueChange = { newPath = it },
            placeholder = { Text("e.g. src/main.kt") },
            singleLine = true,
            textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 15.sp),
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
              fullScreenEditing = true
            } else {
              Toast.makeText(context, "Extension not allowed", Toast.LENGTH_SHORT).show()
            }
          }
          showNewFileDialog = false
        }) { Text("CREATE", color = Color.White, fontFamily = FontFamily.Monospace) }
      },
      dismissButton = {
        TextButton(onClick = { showNewFileDialog = false }) { Text("CANCEL", fontFamily = FontFamily.Monospace) }
      },
    )
  }

  // ── Rename Dialog ────────────────────────────────────────────────────
  if (showRenameDialog && renameTarget != null) {
    var newName by remember { mutableStateOf(renameTarget?.substringAfterLast('/') ?: "") }
    AlertDialog(
      onDismissRequest = { showRenameDialog = false; renameTarget = null },
      title = { Text("RENAME FILE", fontFamily = FontFamily.Monospace, color = Color.White) },
      text = {
        OutlinedTextField(
          value = newName, onValueChange = { newName = it },
          singleLine = true,
          textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 15.sp),
        )
      },
      confirmButton = {
        TextButton(onClick = {
          val target = renameTarget
          val newNameTrimmed = newName.trim()
          if (target != null && newNameTrimmed.isNotBlank()) {
            val parent = target.substringBeforeLast('/', "")
            val newPath = if (parent.isNotEmpty()) "$parent/$newNameTrimmed" else newNameTrimmed
            val srcFile = File(fileBoxManager.root, target)
            val dstFile = File(fileBoxManager.root, newPath)
            if (srcFile.exists() && srcFile.renameTo(dstFile)) {
              if (selectedFilePath == target) selectedFilePath = newPath
              refreshKey++
              Toast.makeText(context, "Renamed", Toast.LENGTH_SHORT).show()
            } else {
              Toast.makeText(context, "Rename failed", Toast.LENGTH_SHORT).show()
            }
          }
          showRenameDialog = false; renameTarget = null
        }) { Text("RENAME", color = Color.White, fontFamily = FontFamily.Monospace) }
      },
      dismissButton = {
        TextButton(onClick = { showRenameDialog = false; renameTarget = null }) {
          Text("CANCEL", fontFamily = FontFamily.Monospace)
        }
      },
    )
  }

  // ── Delete Confirmation ──────────────────────────────────────────────
  if (showDeleteConfirm) {
    AlertDialog(
      onDismissRequest = { showDeleteConfirm = false },
      title = { Text("DELETE FILE?", fontFamily = FontFamily.Monospace, color = Color.Red) },
      text = {
        Text(
          "Delete '${selectedFilePath}'?\nThis cannot be undone.",
          fontFamily = FontFamily.Monospace, fontSize = 13.sp,
        )
      },
      confirmButton = {
        TextButton(onClick = {
          selectedFilePath?.let { fileBoxManager.deleteFile(it) }
          selectedFilePath = null
          editorContent = ""
          editorDirty = false
          fullScreenEditing = false
          refreshKey++
          showDeleteConfirm = false
        }) { Text("DELETE", color = Color.Red, fontFamily = FontFamily.Monospace) }
      },
      dismissButton = {
        TextButton(onClick = { showDeleteConfirm = false }) { Text("CANCEL", fontFamily = FontFamily.Monospace) }
      },
    )
  }
}

// ── Toolbar button helper ────────────────────────────────────────────────────

@Composable
private fun ToolbarButton(
  label: String,
  icon: (@Composable () -> Unit)? = null,
  highlight: Boolean = false,
  danger: Boolean = false,
  onClick: () -> Unit,
) {
  val contentColor = when {
    danger -> Color.Red.copy(alpha = 0.9f)
    highlight -> neonGreen
    else -> neonGreen.copy(alpha = 0.8f)
  }
  OutlinedButton(
    onClick = onClick,
    colors = ButtonDefaults.outlinedButtonColors(contentColor = contentColor),
    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 4.dp),
    modifier = Modifier.height(32.dp),
  ) {
    if (icon != null) {
      icon()
      Spacer(Modifier.width(4.dp))
    }
    Text(label, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
  }
}

// ── File tree node ───────────────────────────────────────────────────────────

@Composable
private fun FileTreeNode(
  node: FileNode,
  depth: Int,
  selectedPath: String?,
  onFileClicked: (String) -> Unit,
) {
  val indent = 10.dp * depth
  var expanded by remember { mutableStateOf(depth < 2) }

  if (node.isDirectory) {
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .clickable { expanded = !expanded }
        .padding(start = indent, top = 3.dp, bottom = 3.dp, end = 4.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Icon(
        if (expanded) Icons.Default.FolderOpen else Icons.Default.Folder,
        contentDescription = null,
        tint = neonGreen.copy(alpha = 0.75f),
        modifier = Modifier.size(13.dp),
      )
      Spacer(Modifier.width(4.dp))
      Text(
        node.name,
        fontFamily = FontFamily.Monospace,
        fontSize = 12.sp,
        color = Color.White.copy(alpha = 0.85f),
      )
    }
    if (expanded) {
      for (child in node.children) {
        FileTreeNode(child, depth + 1, selectedPath, onFileClicked)
      }
    }
  } else {
    val isSelected = node.relativePath == selectedPath
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .background(if (isSelected) neonGreen.copy(alpha = 0.12f) else Color.Transparent)
        .clickable { onFileClicked(node.relativePath) }
        .padding(start = indent, top = 3.dp, bottom = 3.dp, end = 4.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Icon(
        Icons.AutoMirrored.Filled.InsertDriveFile,
        contentDescription = null,
        tint = if (isSelected) neonGreen else neonGreen.copy(alpha = 0.5f),
        modifier = Modifier.size(12.dp),
      )
      Spacer(Modifier.width(4.dp))
      Text(
        node.name,
        fontFamily = FontFamily.Monospace,
        fontSize = 12.sp,
        color = if (isSelected) Color.White else Color.White.copy(alpha = 0.65f),
        maxLines = 1,
      )
    }
  }
}


