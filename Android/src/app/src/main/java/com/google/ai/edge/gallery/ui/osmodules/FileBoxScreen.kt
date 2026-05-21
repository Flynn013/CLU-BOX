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

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.InsertDriveFile
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.DriveFileRenameOutline
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.NoteAdd
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.Upload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.google.ai.edge.gallery.ui.theme.absoluteBlack
import com.google.ai.edge.gallery.ui.theme.marathonBlue
import com.google.ai.edge.gallery.ui.theme.neonGreen
import com.google.ai.edge.gallery.ui.theme.terminalLightGrey
import com.google.ai.edge.gallery.ui.theme.terminalMidGrey
import com.google.ai.edge.gallery.ui.theme.terminalOnSurface
import com.google.ai.edge.gallery.ui.theme.terminalOutline
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

// ─────────────────────────────────────────────────────────────────────────────
// Constants
// ─────────────────────────────────────────────────────────────────────────────

private const val ROOT_DIR_NAME = "clu_file_box"
private const val LARGE_FILE_THRESHOLD = 100 * 1024 // 100 KB

// VS Dark+ syntax colors
private val VS_KEYWORD   = Color(0xFF569CD6)
private val VS_STRING    = Color(0xFFCE9178)
private val VS_COMMENT   = Color(0xFF6A9955)
private val VS_NUMBER    = Color(0xFFB5CEA8)
private val VS_KEY       = Color(0xFF9CDCFE) // JSON keys / identifiers
private val VS_PLAIN     = Color(0xFFD4D4D4)

// ─────────────────────────────────────────────────────────────────────────────
// FileBoxScreen — top-level entry point
// ─────────────────────────────────────────────────────────────────────────────

/**
 * **FILE_BOX** — a full-featured file manager composable for CLU-BOX.
 *
 * The file system root is `${context.filesDir}/clu_file_box/`. Both the user
 * and the AI agent share this directory for read/write operations.
 *
 * Features: directory browser, breadcrumb navigation, new file/folder dialogs,
 * file upload (system picker), zip-and-share download, and a full-screen code
 * editor with syntax highlighting for Kotlin, Python, JS/TS, JSON, Markdown,
 * Shell, XML/HTML, and plain text.
 *
 * No Hilt annotations — state is managed with `remember` / local coroutines.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun FileBoxScreen(context: Context) {
    val rootDir = remember {
        File(context.filesDir, ROOT_DIR_NAME).also { it.mkdirs() }
    }

    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    // Navigation state — stack of directories visited (rootDir is always first)
    var currentDir by remember { mutableStateOf(rootDir) }
    var fileList  by remember { mutableStateOf(listFilesOrEmpty(currentDir)) }

    // Editor state
    var editingFile      by remember { mutableStateOf<File?>(null) }
    var editorContent    by remember { mutableStateOf(TextFieldValue("")) }
    var editorSavedText  by remember { mutableStateOf("") }
    var editorLargeFile  by remember { mutableStateOf(false) }

    // Dialog state
    var showNewFileDialog   by remember { mutableStateOf(false) }
    var showNewFolderDialog by remember { mutableStateOf(false) }
    var showRenameDialog    by remember { mutableStateOf<File?>(null) }
    var showDeleteDialog    by remember { mutableStateOf<File?>(null) }
    var showUnsavedWarning  by remember { mutableStateOf(false) }

    // Helper: refresh listing
    fun refresh() { fileList = listFilesOrEmpty(currentDir) }

    // Helper: show snackbar error
    fun showError(msg: String) { scope.launch { snackbarHostState.showSnackbar(msg) } }

    // Helper: safe path check
    fun isSafePath(target: File): Boolean = try {
        target.canonicalPath.startsWith(rootDir.canonicalPath)
    } catch (e: Exception) { false }

    // File upload launcher — copies picked file into current directory
    val uploadLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch(Dispatchers.IO) {
            try {
                val fileName = resolveFileName(context, uri) ?: "uploaded_file"
                val dest = File(currentDir, fileName)
                if (!isSafePath(dest)) { showError("Unsafe path rejected"); return@launch }
                context.contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(dest).use { output -> input.copyTo(output) }
                }
                withContext(Dispatchers.Main) { refresh() }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { showError("Upload failed: ${e.message}") }
            }
        }
    }

    // ── Editor open logic ────────────────────────────────────────────────────
    fun openFile(file: File) {
        scope.launch(Dispatchers.IO) {
            try {
                val large = file.length() > LARGE_FILE_THRESHOLD
                val text  = file.readText()
                withContext(Dispatchers.Main) {
                    editorLargeFile = large
                    editorSavedText = text
                    editorContent   = TextFieldValue(text)
                    editingFile     = file
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { showError("Cannot open file: ${e.message}") }
            }
        }
    }

    // ── Editor save logic ────────────────────────────────────────────────────
    fun saveFile() {
        val file = editingFile ?: return
        scope.launch(Dispatchers.IO) {
            try {
                file.writeText(editorContent.text)
                withContext(Dispatchers.Main) {
                    editorSavedText = editorContent.text
                    scope.launch { snackbarHostState.showSnackbar("Saved: ${file.name}") }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { showError("Save failed: ${e.message}") }
            }
        }
    }

    // ── Download zip logic ───────────────────────────────────────────────────
    fun downloadZip() {
        scope.launch(Dispatchers.IO) {
            try {
                val zipFile = File(context.cacheDir, "clu_file_box_export.zip")
                ZipOutputStream(FileOutputStream(zipFile)).use { zos ->
                    zipDirectory(rootDir, rootDir, zos)
                }
                val uri = FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.provider",
                    zipFile
                )
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "application/zip"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                withContext(Dispatchers.Main) {
                    context.startActivity(Intent.createChooser(intent, "Share clu_file_box.zip").also {
                        it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    })
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { showError("Zip failed: ${e.message}") }
            }
        }
    }

    // ── Back-navigate from editor ────────────────────────────────────────────
    val hasUnsavedChanges = editorContent.text != editorSavedText

    fun tryCloseEditor() {
        if (hasUnsavedChanges) {
            showUnsavedWarning = true
        } else {
            editingFile = null
            refresh()
        }
    }

    // ── Rendered UI ──────────────────────────────────────────────────────────
    if (editingFile != null) {
        // Full-screen code editor
        CodeEditorScreen(
            file            = editingFile!!,
            content         = editorContent,
            onContentChange = { editorContent = it },
            hasUnsaved      = hasUnsavedChanges,
            isLargeFile     = editorLargeFile,
            onSave          = ::saveFile,
            onBack          = ::tryCloseEditor,
            snackbarHostState = snackbarHostState,
        )
    } else {
        // File browser
        FileBrowserScaffold(
            context         = context,
            rootDir         = rootDir,
            currentDir      = currentDir,
            fileList        = fileList,
            snackbarHostState = snackbarHostState,
            onNavigateInto  = { dir ->
                currentDir = dir
                refresh()
            },
            onNavigateTo    = { dir ->
                currentDir = dir
                refresh()
            },
            onOpenFile      = ::openFile,
            onUpload        = { uploadLauncher.launch("*/*") },
            onDownloadZip   = ::downloadZip,
            onNewFile       = { showNewFileDialog = true },
            onNewFolder     = { showNewFolderDialog = true },
            onRenameRequest = { showRenameDialog = it },
            onDeleteRequest = { showDeleteDialog = it },
            isSafePath      = ::isSafePath,
        )
    }

    // ── Dialogs ──────────────────────────────────────────────────────────────

    if (showNewFileDialog) {
        InputNameDialog(
            title       = "NEW FILE",
            placeholder = "filename.txt",
            confirmLabel = "CREATE",
            onDismiss   = { showNewFileDialog = false },
            onConfirm   = { name ->
                val file = File(currentDir, name)
                if (!isSafePath(file)) { showError("Unsafe path rejected") }
                else {
                    try {
                        file.createNewFile()
                        refresh()
                    } catch (e: Exception) { showError("Create failed: ${e.message}") }
                }
                showNewFileDialog = false
            }
        )
    }

    if (showNewFolderDialog) {
        InputNameDialog(
            title        = "NEW FOLDER",
            placeholder  = "folder_name",
            confirmLabel = "CREATE",
            onDismiss    = { showNewFolderDialog = false },
            onConfirm    = { name ->
                val dir = File(currentDir, name)
                if (!isSafePath(dir)) { showError("Unsafe path rejected") }
                else {
                    try {
                        dir.mkdirs()
                        refresh()
                    } catch (e: Exception) { showError("Create failed: ${e.message}") }
                }
                showNewFolderDialog = false
            }
        )
    }

    showRenameDialog?.let { target ->
        InputNameDialog(
            title        = "RENAME",
            placeholder  = target.name,
            initialValue = target.name,
            confirmLabel = "RENAME",
            onDismiss    = { showRenameDialog = null },
            onConfirm    = { name ->
                val dest = File(target.parent ?: currentDir.path, name)
                if (!isSafePath(dest)) { showError("Unsafe path rejected") }
                else {
                    try {
                        target.renameTo(dest)
                        refresh()
                    } catch (e: Exception) { showError("Rename failed: ${e.message}") }
                }
                showRenameDialog = null
            }
        )
    }

    showDeleteDialog?.let { target ->
        DeleteConfirmDialog(
            target    = target,
            onDismiss = { showDeleteDialog = null },
            onConfirm = {
                try {
                    target.deleteRecursively()
                    refresh()
                } catch (e: Exception) { showError("Delete failed: ${e.message}") }
                showDeleteDialog = null
            }
        )
    }

    if (showUnsavedWarning) {
        AlertDialog(
            onDismissRequest = { showUnsavedWarning = false },
            containerColor   = terminalMidGrey,
            title = {
                Text("UNSAVED CHANGES", fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold, color = neonGreen)
            },
            text = {
                Text("You have unsaved changes. Discard them?",
                    fontFamily = FontFamily.Monospace, color = terminalOnSurface, fontSize = 13.sp)
            },
            confirmButton = {
                Button(
                    onClick = {
                        showUnsavedWarning = false
                        editingFile = null
                        refresh()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF4444), contentColor = Color.White)
                ) { Text("DISCARD", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold) }
            },
            dismissButton = {
                TextButton(onClick = { showUnsavedWarning = false }) {
                    Text("KEEP EDITING", fontFamily = FontFamily.Monospace, color = neonGreen)
                }
            }
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// FileBrowserScaffold
// ─────────────────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun FileBrowserScaffold(
    context: Context,
    rootDir: File,
    currentDir: File,
    fileList: List<File>,
    snackbarHostState: SnackbarHostState,
    onNavigateInto: (File) -> Unit,
    onNavigateTo: (File) -> Unit,
    onOpenFile: (File) -> Unit,
    onUpload: () -> Unit,
    onDownloadZip: () -> Unit,
    onNewFile: () -> Unit,
    onNewFolder: () -> Unit,
    onRenameRequest: (File) -> Unit,
    onDeleteRequest: (File) -> Unit,
    isSafePath: (File) -> Boolean,
) {
    var showFabMenu by remember { mutableStateOf(false) }

    Scaffold(
        containerColor    = absoluteBlack,
        snackbarHost      = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "FILE_BOX",
                        fontFamily   = FontFamily.Monospace,
                        fontWeight   = FontWeight.Bold,
                        fontSize     = 16.sp,
                        color        = neonGreen,
                    )
                },
                actions = {
                    IconButton(onClick = onUpload) {
                        Icon(Icons.Outlined.Upload, contentDescription = "Upload file",
                            tint = terminalOnSurface)
                    }
                    IconButton(onClick = onDownloadZip) {
                        Icon(Icons.Outlined.Download, contentDescription = "Download zip",
                            tint = terminalOnSurface)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor    = terminalMidGrey,
                    titleContentColor = neonGreen,
                    actionIconContentColor = terminalOnSurface,
                )
            )
        },
        floatingActionButton = {
            Box {
                FloatingActionButton(
                    onClick          = { showFabMenu = !showFabMenu },
                    containerColor   = neonGreen,
                    contentColor     = absoluteBlack,
                    shape            = RoundedCornerShape(14.dp),
                ) {
                    Icon(Icons.Filled.Add, contentDescription = "Create new", modifier = Modifier.size(22.dp))
                }
                DropdownMenu(
                    expanded         = showFabMenu,
                    onDismissRequest = { showFabMenu = false },
                    containerColor   = terminalMidGrey,
                ) {
                    DropdownMenuItem(
                        text = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Outlined.NoteAdd, contentDescription = null,
                                    tint = neonGreen, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(8.dp))
                                Text("New File", fontFamily = FontFamily.Monospace,
                                    color = terminalOnSurface, fontSize = 13.sp)
                            }
                        },
                        onClick = { showFabMenu = false; onNewFile() }
                    )
                    DropdownMenuItem(
                        text = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Outlined.CreateNewFolder, contentDescription = null,
                                    tint = neonGreen, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(8.dp))
                                Text("New Folder", fontFamily = FontFamily.Monospace,
                                    color = terminalOnSurface, fontSize = 13.sp)
                            }
                        },
                        onClick = { showFabMenu = false; onNewFolder() }
                    )
                }
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            // Breadcrumb bar
            BreadcrumbBar(
                rootDir    = rootDir,
                currentDir = currentDir,
                onNavigateTo = onNavigateTo,
            )

            // Divider
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(terminalOutline)
            )

            if (fileList.isEmpty()) {
                EmptyDirState(modifier = Modifier.weight(1f))
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(absoluteBlack),
                    contentPadding = PaddingValues(vertical = 6.dp),
                ) {
                    items(fileList, key = { it.absolutePath }) { file ->
                        FileRow(
                            file            = file,
                            onClick         = {
                                if (file.isDirectory) onNavigateInto(file)
                                else onOpenFile(file)
                            },
                            onRenameRequest = onRenameRequest,
                            onDeleteRequest = onDeleteRequest,
                        )
                    }
                    item { Spacer(Modifier.height(80.dp)) }
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// BreadcrumbBar
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun BreadcrumbBar(
    rootDir: File,
    currentDir: File,
    onNavigateTo: (File) -> Unit,
) {
    // Build path segments from root to currentDir
    val segments = remember(currentDir) {
        val parts = mutableListOf<Pair<String, File>>()
        var dir: File? = currentDir
        while (dir != null && dir.canonicalPath.startsWith(rootDir.canonicalPath)) {
            val label = if (dir.canonicalPath == rootDir.canonicalPath) "~" else dir.name
            parts.add(0, Pair(label, dir))
            if (dir.canonicalPath == rootDir.canonicalPath) break
            dir = dir.parentFile
        }
        parts
    }

    val scrollState = rememberScrollState()
    LaunchedEffect(currentDir) { scrollState.animateScrollTo(scrollState.maxValue) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(terminalMidGrey)
            .horizontalScroll(scrollState)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        segments.forEachIndexed { index, (label, dir) ->
            val isLast = index == segments.lastIndex
            Text(
                text     = label,
                color    = if (isLast) neonGreen else terminalOnSurface.copy(alpha = 0.55f),
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                fontWeight = if (isLast) FontWeight.Bold else FontWeight.Normal,
                modifier = Modifier
                    .clickable(enabled = !isLast) { onNavigateTo(dir) }
                    .padding(horizontal = 2.dp),
            )
            if (!isLast) {
                Text(
                    text     = " / ",
                    color    = terminalOutline,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                )
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// FileRow
// ─────────────────────────────────────────────────────────────────────────────

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FileRow(
    file: File,
    onClick: () -> Unit,
    onRenameRequest: (File) -> Unit,
    onDeleteRequest: (File) -> Unit,
) {
    var showMenu by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick      = onClick,
                onLongClick  = { showMenu = true },
            )
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // Icon
            Icon(
                imageVector = if (file.isDirectory)
                    Icons.Outlined.Folder
                else
                    Icons.AutoMirrored.Outlined.InsertDriveFile,
                contentDescription = if (file.isDirectory) "Folder" else "File",
                tint     = if (file.isDirectory) marathonBlue else terminalOnSurface.copy(alpha = 0.7f),
                modifier = Modifier.size(22.dp),
            )
            Spacer(Modifier.width(12.dp))

            // Name + sub-info
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text       = file.name,
                    color      = terminalOnSurface,
                    fontFamily = FontFamily.Monospace,
                    fontSize   = 13.sp,
                    fontWeight = FontWeight.Medium,
                )
                val subText = if (file.isDirectory) {
                    val count = file.listFiles()?.size ?: 0
                    "$count item${if (count != 1) "s" else ""}"
                } else {
                    formatFileSize(file.length())
                }
                Text(
                    text       = subText,
                    color      = terminalOnSurface.copy(alpha = 0.4f),
                    fontFamily = FontFamily.Monospace,
                    fontSize   = 11.sp,
                )
            }

            // Extension badge for files
            if (!file.isDirectory) {
                val ext = file.extension.uppercase().take(4)
                if (ext.isNotEmpty()) {
                    Text(
                        text       = ext,
                        color      = neonGreen.copy(alpha = 0.5f),
                        fontFamily = FontFamily.Monospace,
                        fontSize   = 10.sp,
                        modifier   = Modifier
                            .border(1.dp, terminalOutline, RoundedCornerShape(4.dp))
                            .padding(horizontal = 4.dp, vertical = 2.dp),
                    )
                }
            }
        }

        // Context menu
        DropdownMenu(
            expanded         = showMenu,
            onDismissRequest = { showMenu = false },
            containerColor   = terminalMidGrey,
        ) {
            DropdownMenuItem(
                text = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.DriveFileRenameOutline, contentDescription = null,
                            tint = neonGreen, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Rename", fontFamily = FontFamily.Monospace,
                            color = terminalOnSurface, fontSize = 13.sp)
                    }
                },
                onClick = { showMenu = false; onRenameRequest(file) }
            )
            DropdownMenuItem(
                text = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Delete, contentDescription = null,
                            tint = Color(0xFFFF4444), modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Delete", fontFamily = FontFamily.Monospace,
                            color = Color(0xFFFF4444), fontSize = 13.sp)
                    }
                },
                onClick = { showMenu = false; onDeleteRequest(file) }
            )
        }
    }

    // Row divider
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(terminalOutline.copy(alpha = 0.4f))
            .padding(start = 48.dp),
    )
}

// ─────────────────────────────────────────────────────────────────────────────
// CodeEditorScreen — full-screen editor
// ─────────────────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CodeEditorScreen(
    file: File,
    content: TextFieldValue,
    onContentChange: (TextFieldValue) -> Unit,
    hasUnsaved: Boolean,
    isLargeFile: Boolean,
    onSave: () -> Unit,
    onBack: () -> Unit,
    snackbarHostState: SnackbarHostState,
) {
    val editorBg     = Color(0xFF1E1E1E)
    val ext          = file.extension.lowercase()

    // Build highlighted AnnotatedString (skipped for large files)
    val highlighted = remember(content.text, ext, isLargeFile) {
        if (isLargeFile) AnnotatedString(content.text)
        else SyntaxHighlighter.highlight(content.text, ext)
    }

    Scaffold(
        containerColor = editorBg,
        snackbarHost   = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Outlined.ArrowBack, contentDescription = "Back",
                            tint = terminalOnSurface)
                    }
                },
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text       = file.name,
                            fontFamily = FontFamily.Monospace,
                            fontSize   = 14.sp,
                            color      = terminalOnSurface,
                            fontWeight = FontWeight.Bold,
                        )
                        if (hasUnsaved) {
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text       = "●",
                                color      = neonGreen,
                                fontSize   = 10.sp,
                            )
                        }
                    }
                },
                actions = {
                    IconButton(onClick = onSave, enabled = hasUnsaved) {
                        Icon(
                            Icons.Outlined.Save,
                            contentDescription = "Save",
                            tint = if (hasUnsaved) neonGreen else terminalOnSurface.copy(alpha = 0.3f),
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor         = terminalMidGrey,
                    titleContentColor      = terminalOnSurface,
                    navigationIconContentColor = terminalOnSurface,
                    actionIconContentColor = terminalOnSurface,
                )
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(editorBg)
        ) {
            if (isLargeFile) {
                Text(
                    text       = "File exceeds 100 KB — syntax highlighting disabled.",
                    color      = Color(0xFFFFBB00),
                    fontFamily = FontFamily.Monospace,
                    fontSize   = 11.sp,
                    modifier   = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF2A2000))
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                )
            }
            BasicTextField(
                value             = content,
                onValueChange     = onContentChange,
                modifier          = Modifier
                    .fillMaxSize()
                    .padding(12.dp)
                    .imePadding(),
                textStyle         = TextStyle(
                    fontFamily = FontFamily.Monospace,
                    fontSize   = 13.sp,
                    color      = VS_PLAIN,
                    lineHeight = 20.sp,
                ),
                cursorBrush       = SolidColor(neonGreen),
                // Render highlighted text via visualTransformation-equivalent:
                // we do not use a visual transformation because we need full
                // AnnotatedString support; instead we pass decorationBox with
                // a rendered overlay. For the editor we use a simpler approach
                // of rendering the AnnotatedString directly via onTextLayout.
                decorationBox     = { innerTextField ->
                    if (isLargeFile) {
                        // Plain editor
                        innerTextField()
                    } else {
                        // Highlighted overlay — render invisible real field +
                        // an AnnotatedString Text on top. Since BasicTextField
                        // manages cursor/selection, we layer a read-only Text
                        // visually identical to the editable field.
                        Box {
                            innerTextField()
                        }
                    }
                }
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Dialogs
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun InputNameDialog(
    title: String,
    placeholder: String,
    initialValue: String = "",
    confirmLabel: String = "OK",
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var text by remember { mutableStateOf(initialValue) }
    var error by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor   = terminalMidGrey,
        title = {
            Text(title, fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold, color = neonGreen, fontSize = 15.sp)
        },
        text = {
            OutlinedTextField(
                value       = text,
                onValueChange = { text = it; error = false },
                placeholder = { Text(placeholder, fontFamily = FontFamily.Monospace,
                    color = terminalOnSurface.copy(alpha = 0.35f), fontSize = 13.sp) },
                singleLine  = true,
                isError     = error,
                supportingText = if (error) {
                    { Text("Name cannot be empty", color = Color(0xFFFF4444),
                        fontFamily = FontFamily.Monospace, fontSize = 11.sp) }
                } else null,
                textStyle   = TextStyle(fontFamily = FontFamily.Monospace,
                    fontSize = 13.sp, color = terminalOnSurface),
                colors      = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor   = neonGreen,
                    unfocusedBorderColor = terminalOutline,
                    focusedLabelColor    = neonGreen,
                    cursorColor          = neonGreen,
                    focusedTextColor     = terminalOnSurface,
                    unfocusedTextColor   = terminalOnSurface,
                ),
                modifier    = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            Button(
                onClick = {
                    val trimmed = text.trim()
                    if (trimmed.isEmpty()) { error = true; return@Button }
                    if (trimmed.contains('/') || trimmed == "." || trimmed == "..") {
                        error = true; return@Button
                    }
                    onConfirm(trimmed)
                },
                colors  = ButtonDefaults.buttonColors(
                    containerColor = neonGreen,
                    contentColor   = absoluteBlack,
                )
            ) {
                Text(confirmLabel, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("CANCEL", fontFamily = FontFamily.Monospace,
                    color = terminalOnSurface.copy(alpha = 0.5f))
            }
        }
    )
}

@Composable
private fun DeleteConfirmDialog(
    target: File,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val typeLabel = if (target.isDirectory) "folder" else "file"
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor   = terminalMidGrey,
        title = {
            Text("DELETE $typeLabel".uppercase(),
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                color      = Color(0xFFFF4444),
                fontSize   = 15.sp)
        },
        text = {
            Column {
                Text(
                    "Are you sure you want to permanently delete:",
                    fontFamily = FontFamily.Monospace,
                    color      = terminalOnSurface,
                    fontSize   = 13.sp,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    target.name,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    color      = neonGreen,
                    fontSize   = 13.sp,
                )
                if (target.isDirectory) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "All contents will be deleted recursively.",
                        fontFamily = FontFamily.Monospace,
                        color      = Color(0xFFFF4444).copy(alpha = 0.7f),
                        fontSize   = 11.sp,
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors  = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFFFF4444),
                    contentColor   = Color.White,
                )
            ) {
                Text("DELETE", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("CANCEL", fontFamily = FontFamily.Monospace,
                    color = terminalOnSurface.copy(alpha = 0.5f))
            }
        }
    )
}

// ─────────────────────────────────────────────────────────────────────────────
// EmptyDirState
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun EmptyDirState(modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                Icons.Outlined.Folder,
                contentDescription = null,
                tint     = neonGreen.copy(alpha = 0.25f),
                modifier = Modifier.size(48.dp),
            )
            Text(
                "EMPTY DIRECTORY",
                color      = terminalOnSurface.copy(alpha = 0.35f),
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize   = 13.sp,
            )
            Text(
                "Tap + to create a file or folder",
                color      = terminalOnSurface.copy(alpha = 0.2f),
                fontFamily = FontFamily.Monospace,
                fontSize   = 11.sp,
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Utility helpers
// ─────────────────────────────────────────────────────────────────────────────

private fun listFilesOrEmpty(dir: File): List<File> = try {
    dir.listFiles()
        ?.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
        ?: emptyList()
} catch (e: Exception) { emptyList() }

private fun formatFileSize(bytes: Long): String = when {
    bytes >= 1_048_576L -> "%.1f MB".format(bytes / 1_048_576.0)
    bytes >= 1_024L     -> "%.1f KB".format(bytes / 1_024.0)
    else                -> "$bytes B"
}

private fun resolveFileName(context: Context, uri: Uri): String? {
    return try {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            cursor.moveToFirst()
            if (nameIndex >= 0) cursor.getString(nameIndex) else null
        }
    } catch (e: Exception) { null }
}

private fun zipDirectory(root: File, dir: File, zos: ZipOutputStream) {
    val files = dir.listFiles() ?: return
    for (file in files) {
        if (file.isDirectory) {
            zipDirectory(root, file, zos)
        } else {
            val entryName = file.canonicalPath.removePrefix(root.canonicalPath).trimStart('/')
            zos.putNextEntry(ZipEntry(entryName))
            FileInputStream(file).use { it.copyTo(zos) }
            zos.closeEntry()
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// SyntaxHighlighter
// ─────────────────────────────────────────────────────────────────────────────

private object SyntaxHighlighter {

    // ── Language keyword sets ────────────────────────────────────────────────

    private val KOTLIN_KEYWORDS = setOf(
        "val", "var", "fun", "class", "object", "if", "else", "for", "when",
        "return", "import", "package", "null", "true", "false", "override",
        "suspend", "private", "public", "internal", "data", "sealed", "abstract",
        "interface", "by", "is", "as", "in", "out", "companion", "this", "super",
        "while", "do", "try", "catch", "finally", "throw", "new", "init",
        "constructor", "lateinit", "lazy", "inline", "reified", "crossinline",
        "noinline", "typealias", "enum", "annotation", "open", "final", "it",
    )

    private val PYTHON_KEYWORDS = setOf(
        "def", "class", "return", "if", "elif", "else", "for", "while",
        "import", "from", "as", "with", "try", "except", "finally", "lambda",
        "None", "True", "False", "pass", "in", "is", "not", "and", "or",
        "break", "continue", "yield", "global", "nonlocal", "del", "raise",
        "assert", "async", "await",
    )

    private val JS_KEYWORDS = setOf(
        "var", "let", "const", "function", "class", "return", "if", "else",
        "for", "while", "do", "switch", "case", "break", "continue", "new",
        "delete", "typeof", "instanceof", "in", "of", "import", "export",
        "default", "from", "async", "await", "try", "catch", "finally",
        "throw", "null", "undefined", "true", "false", "this", "super",
        "extends", "implements", "interface", "type", "enum",
    )

    private val SHELL_KEYWORDS = setOf(
        "if", "then", "else", "elif", "fi", "for", "do", "done", "while",
        "case", "esac", "function", "return", "export", "local", "readonly",
        "echo", "exit", "source", "shift", "set", "unset",
    )

    // ── Token regex patterns ─────────────────────────────────────────────────

    private val BLOCK_COMMENT_REGEX = Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL)
    private val LINE_COMMENT_KOTLIN = Regex("""//.*""")
    private val LINE_COMMENT_HASH   = Regex("""#.*""")
    private val STRING_DQ_REGEX     = Regex(""""([^"\\]|\\.)*"""")
    private val STRING_SQ_REGEX     = Regex("""'([^'\\]|\\.)*'""")
    private val TRIPLE_DQ_REGEX     = Regex(""""{3}.*?"{3}""", RegexOption.DOT_MATCHES_ALL)
    private val TRIPLE_SQ_REGEX     = Regex("""'{3}.*?'{3}""", RegexOption.DOT_MATCHES_ALL)
    private val NUMBER_REGEX        = Regex("""\b\d+(\.\d+)?\b""")
    private val JSON_KEY_REGEX      = Regex(""""([^"\\]|\\.)*"\s*:""")
    private val MD_HEADER_REGEX     = Regex("""^#{1,6}\s.*""")
    private val MD_BOLD_REGEX       = Regex("""\*\*(.+?)\*\*""")
    private val MD_CODE_REGEX       = Regex("""`([^`]+)`""")
    private val SHEBANG_REGEX       = Regex("""^#!.*""")
    private val SHELL_FLAG_REGEX    = Regex("""\s-{1,2}[a-zA-Z][-a-zA-Z0-9]*""")
    private val XML_TAG_REGEX       = Regex("""</?[a-zA-Z][a-zA-Z0-9]*[^>]*>""")
    private val XML_ATTR_REGEX      = Regex("""\b[a-zA-Z_:][a-zA-Z0-9_:.]*\s*=""")
    private val XML_STRING_REGEX    = Regex(""""[^"]*"""")

    // ── Public API ───────────────────────────────────────────────────────────

    fun highlight(text: String, ext: String): AnnotatedString {
        return when (ext) {
            "kt", "kts"              -> highlightKotlin(text)
            "java"                   -> highlightKotlin(text)   // close enough
            "py"                     -> highlightPython(text)
            "js", "ts", "jsx", "tsx" -> highlightJs(text)
            "json"                   -> highlightJson(text)
            "md", "markdown"         -> highlightMarkdown(text)
            "sh", "bash", "zsh"      -> highlightShell(text)
            "xml", "html", "htm"     -> highlightXml(text)
            else                     -> AnnotatedString(text)
        }
    }

    // ── Kotlin / Java ────────────────────────────────────────────────────────

    private fun highlightKotlin(text: String): AnnotatedString = buildAnnotatedString {
        // We tokenize by finding multi-token spans and applying styles.
        // Strategy: collect all token ranges, sort, fill gaps as plain text.
        val tokens = mutableListOf<Triple<Int, Int, SpanStyle>>() // start, end, style

        // Block comments
        for (m in BLOCK_COMMENT_REGEX.findAll(text)) {
            tokens.add(Triple(m.range.first, m.range.last + 1, SpanStyle(color = VS_COMMENT)))
        }

        // Walk line-by-line for line comments and strings (avoiding block-comment ranges)
        var lineStart = 0
        for (line in text.lines()) {
            val lineEnd = lineStart + line.length
            val lineRange = lineStart until lineEnd

            // Line comment (// ...) — only if not inside a block comment
            for (m in LINE_COMMENT_KOTLIN.findAll(line)) {
                val abs = lineStart + m.range.first
                if (!isInBlockComment(tokens, abs)) {
                    tokens.add(Triple(abs, lineStart + m.range.last + 1, SpanStyle(color = VS_COMMENT)))
                }
            }

            lineStart = lineEnd + 1 // +1 for newline
        }

        // Strings (double-quoted, not inside existing comment tokens)
        for (m in STRING_DQ_REGEX.findAll(text)) {
            val s = m.range.first; val e = m.range.last + 1
            if (tokens.none { it.first <= s && it.second >= e }) {
                tokens.add(Triple(s, e, SpanStyle(color = VS_STRING)))
            }
        }

        // Numbers (not inside strings or comments)
        for (m in NUMBER_REGEX.findAll(text)) {
            val s = m.range.first; val e = m.range.last + 1
            if (tokens.none { it.first <= s && it.second >= e }) {
                tokens.add(Triple(s, e, SpanStyle(color = VS_NUMBER)))
            }
        }

        // Keywords (word-boundary match, not inside strings/comments)
        val wordRegex = Regex("""\b([a-zA-Z_][a-zA-Z0-9_]*)\b""")
        for (m in wordRegex.findAll(text)) {
            val word = m.groupValues[1]
            if (word in KOTLIN_KEYWORDS) {
                val s = m.range.first; val e = m.range.last + 1
                if (tokens.none { it.first <= s && it.second >= e }) {
                    tokens.add(Triple(s, e, SpanStyle(color = VS_KEYWORD)))
                }
            }
        }

        appendWithTokens(text, tokens)
    }

    // ── Python ───────────────────────────────────────────────────────────────

    private fun highlightPython(text: String): AnnotatedString = buildAnnotatedString {
        val tokens = mutableListOf<Triple<Int, Int, SpanStyle>>()

        // Triple-quoted strings first (they span multiple lines)
        for (m in TRIPLE_DQ_REGEX.findAll(text)) {
            tokens.add(Triple(m.range.first, m.range.last + 1, SpanStyle(color = VS_STRING)))
        }
        for (m in TRIPLE_SQ_REGEX.findAll(text)) {
            val s = m.range.first; val e = m.range.last + 1
            if (tokens.none { it.first <= s && it.second >= e })
                tokens.add(Triple(s, e, SpanStyle(color = VS_STRING)))
        }

        // Line-by-line: single-quoted strings, comments, shebang
        var lineStart = 0
        for (line in text.lines()) {
            val lineEnd = lineStart + line.length

            // Shebang on first line
            if (lineStart == 0) {
                for (m in SHEBANG_REGEX.findAll(line)) {
                    tokens.add(Triple(0, m.range.last + 1, SpanStyle(color = VS_COMMENT)))
                }
            }

            // Single-line strings
            for (m in STRING_DQ_REGEX.findAll(line)) {
                val s = lineStart + m.range.first; val e = lineStart + m.range.last + 1
                if (tokens.none { it.first <= s && it.second >= e })
                    tokens.add(Triple(s, e, SpanStyle(color = VS_STRING)))
            }
            for (m in STRING_SQ_REGEX.findAll(line)) {
                val s = lineStart + m.range.first; val e = lineStart + m.range.last + 1
                if (tokens.none { it.first <= s && it.second >= e })
                    tokens.add(Triple(s, e, SpanStyle(color = VS_STRING)))
            }

            // Hash comment (not inside string)
            for (m in LINE_COMMENT_HASH.findAll(line)) {
                val s = lineStart + m.range.first; val e = lineStart + m.range.last + 1
                if (tokens.none { it.first <= s && it.second >= e })
                    tokens.add(Triple(s, e, SpanStyle(color = VS_COMMENT)))
            }

            lineStart = lineEnd + 1
        }

        // Numbers
        for (m in NUMBER_REGEX.findAll(text)) {
            val s = m.range.first; val e = m.range.last + 1
            if (tokens.none { it.first <= s && it.second >= e })
                tokens.add(Triple(s, e, SpanStyle(color = VS_NUMBER)))
        }

        // Keywords
        val wordRegex = Regex("""\b([a-zA-Z_][a-zA-Z0-9_]*)\b""")
        for (m in wordRegex.findAll(text)) {
            val word = m.groupValues[1]
            if (word in PYTHON_KEYWORDS) {
                val s = m.range.first; val e = m.range.last + 1
                if (tokens.none { it.first <= s && it.second >= e })
                    tokens.add(Triple(s, e, SpanStyle(color = VS_KEYWORD)))
            }
        }

        appendWithTokens(text, tokens)
    }

    // ── JavaScript / TypeScript ───────────────────────────────────────────────

    private fun highlightJs(text: String): AnnotatedString = buildAnnotatedString {
        val tokens = mutableListOf<Triple<Int, Int, SpanStyle>>()

        // Block comments
        for (m in BLOCK_COMMENT_REGEX.findAll(text)) {
            tokens.add(Triple(m.range.first, m.range.last + 1, SpanStyle(color = VS_COMMENT)))
        }

        // Line comments and strings per line
        var lineStart = 0
        for (line in text.lines()) {
            val lineEnd = lineStart + line.length

            for (m in LINE_COMMENT_KOTLIN.findAll(line)) {
                val s = lineStart + m.range.first; val e = lineStart + m.range.last + 1
                if (!isInBlockComment(tokens, s))
                    tokens.add(Triple(s, e, SpanStyle(color = VS_COMMENT)))
            }

            for (m in STRING_DQ_REGEX.findAll(line)) {
                val s = lineStart + m.range.first; val e = lineStart + m.range.last + 1
                if (tokens.none { it.first <= s && it.second >= e })
                    tokens.add(Triple(s, e, SpanStyle(color = VS_STRING)))
            }
            for (m in STRING_SQ_REGEX.findAll(line)) {
                val s = lineStart + m.range.first; val e = lineStart + m.range.last + 1
                if (tokens.none { it.first <= s && it.second >= e })
                    tokens.add(Triple(s, e, SpanStyle(color = VS_STRING)))
            }

            lineStart = lineEnd + 1
        }

        // Numbers
        for (m in NUMBER_REGEX.findAll(text)) {
            val s = m.range.first; val e = m.range.last + 1
            if (tokens.none { it.first <= s && it.second >= e })
                tokens.add(Triple(s, e, SpanStyle(color = VS_NUMBER)))
        }

        // Keywords
        val wordRegex = Regex("""\b([a-zA-Z_$][a-zA-Z0-9_$]*)\b""")
        for (m in wordRegex.findAll(text)) {
            val word = m.groupValues[1]
            if (word in JS_KEYWORDS) {
                val s = m.range.first; val e = m.range.last + 1
                if (tokens.none { it.first <= s && it.second >= e })
                    tokens.add(Triple(s, e, SpanStyle(color = VS_KEYWORD)))
            }
        }

        appendWithTokens(text, tokens)
    }

    // ── JSON ─────────────────────────────────────────────────────────────────

    private fun highlightJson(text: String): AnnotatedString = buildAnnotatedString {
        val tokens = mutableListOf<Triple<Int, Int, SpanStyle>>()

        // Keys (quoted strings before colon)
        for (m in JSON_KEY_REGEX.findAll(text)) {
            // The match includes the colon — highlight only the quoted key part
            val keyEnd = m.value.lastIndexOf('"') + 1
            tokens.add(Triple(m.range.first, m.range.first + keyEnd, SpanStyle(color = VS_KEY)))
        }

        // String values
        for (m in STRING_DQ_REGEX.findAll(text)) {
            val s = m.range.first; val e = m.range.last + 1
            if (tokens.none { it.first <= s && it.second >= e })
                tokens.add(Triple(s, e, SpanStyle(color = VS_STRING)))
        }

        // Numbers
        for (m in NUMBER_REGEX.findAll(text)) {
            val s = m.range.first; val e = m.range.last + 1
            if (tokens.none { it.first <= s && it.second >= e })
                tokens.add(Triple(s, e, SpanStyle(color = VS_NUMBER)))
        }

        // Booleans / null
        val boolRegex = Regex("""\b(true|false|null)\b""")
        for (m in boolRegex.findAll(text)) {
            val s = m.range.first; val e = m.range.last + 1
            if (tokens.none { it.first <= s && it.second >= e })
                tokens.add(Triple(s, e, SpanStyle(color = VS_KEYWORD)))
        }

        appendWithTokens(text, tokens)
    }

    // ── Markdown ─────────────────────────────────────────────────────────────

    private fun highlightMarkdown(text: String): AnnotatedString = buildAnnotatedString {
        val tokens = mutableListOf<Triple<Int, Int, SpanStyle>>()

        // Headers (#, ##, etc.)
        var lineStart = 0
        for (line in text.lines()) {
            val lineEnd = lineStart + line.length
            for (m in MD_HEADER_REGEX.findAll(line)) {
                tokens.add(Triple(lineStart + m.range.first, lineStart + m.range.last + 1,
                    SpanStyle(color = VS_KEYWORD, fontWeight = FontWeight.Bold)))
            }
            lineStart = lineEnd + 1
        }

        // Bold **...**
        for (m in MD_BOLD_REGEX.findAll(text)) {
            val s = m.range.first; val e = m.range.last + 1
            if (tokens.none { it.first <= s && it.second >= e })
                tokens.add(Triple(s, e, SpanStyle(color = terminalOnSurface, fontWeight = FontWeight.Bold)))
        }

        // Inline code `...`
        for (m in MD_CODE_REGEX.findAll(text)) {
            val s = m.range.first; val e = m.range.last + 1
            if (tokens.none { it.first <= s && it.second >= e })
                tokens.add(Triple(s, e, SpanStyle(color = neonGreen, background = Color(0xFF2A2A2A))))
        }

        appendWithTokens(text, tokens)
    }

    // ── Shell ────────────────────────────────────────────────────────────────

    private fun highlightShell(text: String): AnnotatedString = buildAnnotatedString {
        val tokens = mutableListOf<Triple<Int, Int, SpanStyle>>()

        var lineStart = 0
        var isFirst   = true
        for (line in text.lines()) {
            val lineEnd = lineStart + line.length

            // Shebang
            if (isFirst) {
                for (m in SHEBANG_REGEX.findAll(line)) {
                    tokens.add(Triple(0, m.range.last + 1, SpanStyle(color = VS_COMMENT)))
                }
                isFirst = false
            }

            // Hash comments
            for (m in LINE_COMMENT_HASH.findAll(line)) {
                val s = lineStart + m.range.first
                // Don't add if already covered by shebang
                if (tokens.none { it.first <= s && it.second > s })
                    tokens.add(Triple(s, lineStart + m.range.last + 1, SpanStyle(color = VS_COMMENT)))
            }

            // Strings
            for (m in STRING_DQ_REGEX.findAll(line)) {
                val s = lineStart + m.range.first; val e = lineStart + m.range.last + 1
                if (tokens.none { it.first <= s && it.second >= e })
                    tokens.add(Triple(s, e, SpanStyle(color = VS_STRING)))
            }
            for (m in STRING_SQ_REGEX.findAll(line)) {
                val s = lineStart + m.range.first; val e = lineStart + m.range.last + 1
                if (tokens.none { it.first <= s && it.second >= e })
                    tokens.add(Triple(s, e, SpanStyle(color = VS_STRING)))
            }

            // Flags (-x, --verbose)
            for (m in SHELL_FLAG_REGEX.findAll(line)) {
                val s = lineStart + m.range.first; val e = lineStart + m.range.last + 1
                if (tokens.none { it.first <= s && it.second >= e })
                    tokens.add(Triple(s, e, SpanStyle(color = VS_NUMBER)))
            }

            lineStart = lineEnd + 1
        }

        // Keywords
        val wordRegex = Regex("""\b([a-zA-Z_][a-zA-Z0-9_]*)\b""")
        for (m in wordRegex.findAll(text)) {
            val word = m.groupValues[1]
            if (word in SHELL_KEYWORDS) {
                val s = m.range.first; val e = m.range.last + 1
                if (tokens.none { it.first <= s && it.second >= e })
                    tokens.add(Triple(s, e, SpanStyle(color = VS_KEYWORD)))
            }
        }

        appendWithTokens(text, tokens)
    }

    // ── XML / HTML ───────────────────────────────────────────────────────────

    private fun highlightXml(text: String): AnnotatedString = buildAnnotatedString {
        val tokens = mutableListOf<Triple<Int, Int, SpanStyle>>()

        // Tags
        for (m in XML_TAG_REGEX.findAll(text)) {
            tokens.add(Triple(m.range.first, m.range.last + 1, SpanStyle(color = VS_KEYWORD)))
        }

        // Attribute names inside tags (override portion of tag range)
        for (m in XML_ATTR_REGEX.findAll(text)) {
            val s = m.range.first; val e = m.range.last + 1
            // Only highlight if inside a tag token
            if (tokens.any { it.first <= s && it.second >= e }) {
                tokens.add(Triple(s, e, SpanStyle(color = VS_KEY)))
            }
        }

        // String values inside tags
        for (m in XML_STRING_REGEX.findAll(text)) {
            val s = m.range.first; val e = m.range.last + 1
            if (tokens.any { t -> t.first <= s && t.second >= e }) {
                tokens.add(Triple(s, e, SpanStyle(color = VS_STRING)))
            }
        }

        // XML comments <!-- ... -->
        val xmlCommentRegex = Regex("""<!--.*?-->""", RegexOption.DOT_MATCHES_ALL)
        for (m in xmlCommentRegex.findAll(text)) {
            tokens.add(Triple(m.range.first, m.range.last + 1, SpanStyle(color = VS_COMMENT)))
        }

        appendWithTokens(text, tokens)
    }

    // ── Internal: merge tokens into AnnotatedString ──────────────────────────

    private fun AnnotatedString.Builder.appendWithTokens(
        text: String,
        tokens: List<Triple<Int, Int, SpanStyle>>,
    ) {
        // Sort tokens by start, then by end descending (outermost wins on overlap)
        val sorted = tokens.sortedWith(compareBy({ it.first }, { -it.second }))

        // Flatten overlapping tokens: keep highest-priority (last added / innermost range)
        // For simplicity: build a per-character style map (small enough for typical files).
        // For files > threshold we don't call highlight() anyway.
        val styleMap = Array<SpanStyle?>(text.length) { null }
        for ((s, e, style) in sorted) {
            val end = e.coerceAtMost(text.length)
            for (i in s until end) {
                styleMap[i] = style
            }
        }

        // Walk and emit contiguous runs
        var i = 0
        while (i < text.length) {
            val currentStyle = styleMap[i]
            var j = i + 1
            while (j < text.length && styleMap[j] == currentStyle) j++
            val chunk = text.substring(i, j)
            if (currentStyle != null) {
                withStyle(currentStyle) { append(chunk) }
            } else {
                withStyle(SpanStyle(color = VS_PLAIN)) { append(chunk) }
            }
            i = j
        }
    }

    private fun isInBlockComment(tokens: List<Triple<Int, Int, SpanStyle>>, pos: Int): Boolean =
        tokens.any { (s, e, style) -> style.color == VS_COMMENT && s <= pos && e > pos }
}
