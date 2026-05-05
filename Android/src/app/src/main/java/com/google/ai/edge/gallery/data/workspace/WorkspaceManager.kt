/*
 * Copyright 2026 Flynn013 / CLU/BOX
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

// Workspace directory management adapted from Flynn013/SPL-NTR WorkspaceManager (Apache-2.0),
// simplified and backed by the existing CLU/BOX FileBoxManager infrastructure.

package com.google.ai.edge.gallery.data.workspace

import android.content.Context
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Manages the active project workspace directory used by the agentic loop.
 *
 * The workspace is the root directory that the shell, git, read/write/edit tools
 * and BusyBox operations are scoped to.  The agent sees the workspace as its
 * "current working directory".
 *
 * Two workspace modes are supported:
 * - **Internal** (default): `<filesDir>/workspace/` — always writable, no extra
 *   Android permissions required.  Suitable for most coding and file-editing tasks.
 * - **External** (SAF): a user-selected directory exposed via Android Storage Access
 *   Framework — allows working on files in shared storage / SD cards / USB drives.
 *   When an external URI is active, the workspace root is a Java [File] proxy created
 *   from the granted URI via `DocumentFile`.
 *
 * The active workspace root is observable via [workspaceDir] so the UI can display
 * the current path.
 */
class WorkspaceManager(private val context: Context) {

    companion object {
        private const val TAG = "WorkspaceManager"
        private const val INTERNAL_WORKSPACE = "workspace"
        private const val PREFS_NAME = "workspace_prefs"
        private const val PREF_EXTERNAL_URI = "external_workspace_uri"
    }

    // ── State ──────────────────────────────────────────────────────────────

    private val _workspaceDir = MutableStateFlow(defaultInternalDir())
    /** The currently active workspace root directory. */
    val workspaceDir: StateFlow<File> = _workspaceDir.asStateFlow()

    /** `true` when the workspace points at an external (SAF) directory. */
    val isExternalWorkspace: Boolean
        get() = _workspaceDir.value != defaultInternalDir()

    init {
        // Restore persisted external workspace URI (if any) on first use.
        restorePersistedWorkspace()
    }

    // ── Workspace selection ─────────────────────────────────────────────────

    /**
     * Switches the workspace to the internal default (`<filesDir>/workspace/`).
     * Any persisted external URI is cleared.
     */
    fun useInternalWorkspace() {
        persistUri(null)
        val dir = defaultInternalDir()
        _workspaceDir.value = dir
        Log.d(TAG, "Workspace → internal: ${dir.absolutePath}")
    }

    /**
     * Switches the workspace to the directory identified by [uri] (SAF content URI).
     *
     * The URI's read+write permissions must already be held before calling this.
     * Persists the URI so it survives process death.
     */
    suspend fun useExternalWorkspace(uri: Uri): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            // Resolve the URI to an actual path if possible (works for local volumes)
            val resolvedPath = resolveUriToPath(uri)
            val dir = if (resolvedPath != null) {
                File(resolvedPath).also { it.mkdirs() }
            } else {
                // Fall back to a cache subdirectory named after the last path segment
                val segment = uri.lastPathSegment?.replace(Regex("[^a-zA-Z0-9._-]"), "_")
                    ?: "external"
                File(context.cacheDir, "external_workspace/$segment").also { it.mkdirs() }
            }
            persistUri(uri)
            _workspaceDir.value = dir
            Log.d(TAG, "Workspace → external: ${dir.absolutePath} (uri=$uri)")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "useExternalWorkspace failed", e)
            Result.failure(e)
        }
    }

    // ── File operations ─────────────────────────────────────────────────────

    /**
     * Returns a [File] relative to the current workspace root.
     * The path is sanitised to prevent directory traversal.
     */
    fun resolve(relativePath: String): File {
        val canonical = File(_workspaceDir.value, relativePath).canonicalFile
        val root = _workspaceDir.value.canonicalFile
        return if (canonical.absolutePath.startsWith(root.absolutePath)) {
            canonical
        } else {
            Log.w(TAG, "Path traversal blocked: $relativePath")
            root
        }
    }

    /**
     * Returns a tree listing of the workspace root (up to [maxDepth] levels).
     * Format: one entry per line, directories suffixed with `/`.
     */
    suspend fun tree(maxDepth: Int = 3): String = withContext(Dispatchers.IO) {
        buildString { appendTree(_workspaceDir.value, 0, maxDepth) }
    }

    /**
     * Ensures the internal workspace directory exists.  Called lazily on first access.
     */
    suspend fun ensureInternalWorkspaceExists() = withContext(Dispatchers.IO) {
        defaultInternalDir().mkdirs()
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    private fun defaultInternalDir(): File =
        File(context.filesDir, INTERNAL_WORKSPACE).also { it.mkdirs() }

    private fun prefs() = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun persistUri(uri: Uri?) {
        prefs().edit()
            .also { editor ->
                if (uri != null) editor.putString(PREF_EXTERNAL_URI, uri.toString())
                else editor.remove(PREF_EXTERNAL_URI)
            }
            .apply()
    }

    private fun restorePersistedWorkspace() {
        val uriStr = prefs().getString(PREF_EXTERNAL_URI, null) ?: return
        try {
            val uri = Uri.parse(uriStr)
            val resolvedPath = resolveUriToPath(uri)
            if (resolvedPath != null) {
                val dir = File(resolvedPath)
                if (dir.exists() && dir.isDirectory) {
                    _workspaceDir.value = dir
                    Log.d(TAG, "Restored external workspace: ${dir.absolutePath}")
                    return
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not restore external workspace: ${e.message}")
        }
        // Fall back to internal
        persistUri(null)
    }

    /** Best-effort path resolution for a content URI (works for primary storage). */
    private fun resolveUriToPath(uri: Uri): String? {
        return try {
            when (uri.scheme) {
                "file" -> uri.path
                "content" -> {
                    // For primary external storage URIs (DocumentsContract format)
                    val docId = android.provider.DocumentsContract.getTreeDocumentId(uri)
                    val parts = docId?.split(":") ?: return null
                    if (parts.size < 2) return null
                    val volume = parts[0]
                    val path = parts[1]
                    when (volume) {
                        "primary" -> "${android.os.Environment.getExternalStorageDirectory()}/$path"
                        else -> null
                    }
                }
                else -> null
            }
        } catch (_: Exception) { null }
    }

    private fun StringBuilder.appendTree(dir: File, depth: Int, maxDepth: Int) {
        if (depth > maxDepth) return
        val indent = "  ".repeat(depth)
        val entries = dir.listFiles()?.sortedWith(
            compareBy({ !it.isDirectory }, { it.name.lowercase() })
        ) ?: return
        for (entry in entries) {
            if (entry.isDirectory) {
                appendLine("$indent${entry.name}/")
                appendTree(entry, depth + 1, maxDepth)
            } else {
                val size = when {
                    entry.length() < 1024 -> "${entry.length()}B"
                    entry.length() < 1024 * 1024 -> "${"%.1f".format(entry.length() / 1024.0)}KB"
                    else -> "${"%.1f".format(entry.length() / (1024.0 * 1024))}MB"
                }
                appendLine("$indent${entry.name} ($size)")
            }
        }
    }
}
