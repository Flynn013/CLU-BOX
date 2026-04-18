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

package com.google.ai.edge.gallery.data

import android.content.Context
import android.os.FileObserver
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

private const val TAG = "FileBoxManager"

/** Root directory name inside [Context.getFilesDir]. */
private const val ROOT_DIR_NAME = "clu_file_box"

/** Extensions that are explicitly allowed (text/code only). */
private val ALLOWED_EXTENSIONS = setOf(
  "txt", "kt", "kts", "java", "js", "ts", "jsx", "tsx",
  "json", "md", "html", "htm", "css", "xml", "yaml", "yml",
  "py", "rb", "rs", "go", "c", "cpp", "h", "hpp", "cs",
  "sh", "bash", "zsh", "bat", "ps1",
  "sql", "graphql", "toml", "ini", "cfg", "conf", "env",
  "csv", "log", "gradle", "properties", "pro",
  "swift", "dart", "lua", "r", "scala", "groovy",
  "dockerfile", "makefile", "cmake",
)

/** Extensions that are explicitly rejected (binary / media). */
private val REJECTED_EXTENSIONS = setOf(
  "png", "jpg", "jpeg", "gif", "bmp", "webp", "svg", "ico",
  "mp3", "wav", "ogg", "flac", "aac", "m4a", "wma",
  "mp4", "avi", "mov", "mkv", "webm", "flv", "wmv",
  "zip", "tar", "gz", "rar", "7z", "bz2",
  "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx",
  "apk", "aab", "exe", "dll", "so", "dylib", "bin", "class", "jar",
)

/**
 * Manages a sandboxed file workspace under `<filesDir>/clu_file_box/`.
 *
 * Only text-based file types are permitted. Binary formats (images, audio, video,
 * archives, etc.) are rejected. Nested paths are supported: any missing parent
 * directories are created automatically.
 */
class FileBoxManager(context: Context) {

  val root: File = File(context.filesDir, ROOT_DIR_NAME).also { it.mkdirs() }

  // ── Editor Context (shared with agent tools) ────────────────
  // These flows are updated by FileBoxScreen whenever the user selects a file
  // or moves the cursor. The agent reads them via Workspace_Sync_Snapshot to
  // correlate terminal errors with the active editor position.

  private val _currentFilePath = MutableStateFlow<String?>(null)

  /** The file currently open in the FILE_BOX editor (relative to [root]), or null. */
  val currentFilePath: StateFlow<String?> = _currentFilePath.asStateFlow()

  private val _cursorLine = MutableStateFlow(1)

  /** 1-based line number where the cursor is positioned in the editor. */
  val cursorLine: StateFlow<Int> = _cursorLine.asStateFlow()

  /** Called by the FILE_BOX UI to update the active editor file path. */
  fun setCurrentFile(relativePath: String?) {
    _currentFilePath.value = relativePath
    if (relativePath == null) _cursorLine.value = 1
  }

  /** Called by the FILE_BOX UI to update the cursor line position. */
  fun setCursorLine(line: Int) {
    _cursorLine.value = line.coerceAtLeast(1)
  }

  // ── Inotify Bridge ──────────────────────────────────────────
  // A monotonically-increasing revision counter bumped whenever a file-system
  // event is detected in the sandbox. The FILE_BOX UI collects this flow and
  // rebuilds the tree automatically — even when changes originate from the
  // MSTR_CTRL terminal writing directly to the shared directory.
  //
  // Uses [AtomicInteger] for the counter so concurrent FileObserver events
  // never lose an increment, then publishes through a [StateFlow] for the UI.

  private val _revisionCounter = AtomicInteger(0)
  private val _revision = MutableStateFlow(0)

  /** Collect this in the UI to trigger automatic file-tree rebuilds. */
  val revision: StateFlow<Int> = _revision.asStateFlow()

  /** Recursive FileObserver that watches the entire clu_file_box tree. */
  private val fileObserver: FileObserver = object : FileObserver(
    root,
    CREATE or DELETE or MODIFY or MOVED_FROM or MOVED_TO or CLOSE_WRITE,
  ) {
    override fun onEvent(event: Int, path: String?) {
      Log.d(TAG, "FileObserver event=$event path=$path")
      _revision.value = _revisionCounter.incrementAndGet()
    }
  }

  init {
    fileObserver.startWatching()
  }

  /** Stop watching (called if the manager is ever disposed). */
  fun stopWatching() {
    fileObserver.stopWatching()
  }

  // ── Extension validation ─────────────────────────────────────

  /** Returns `true` if [path] has an allowed text/code extension. */
  fun isAllowedExtension(path: String): Boolean {
    val ext = path.substringAfterLast('.', "").lowercase()
    if (ext.isEmpty()) return false
    if (ext in REJECTED_EXTENSIONS) return false
    return ext in ALLOWED_EXTENSIONS
  }

  /**
   * Returns `true` if the resolved [target] file is safely within the sandbox [root].
   * Uses [File.canonicalFile] comparison to block directory-traversal attacks
   * (e.g., `../../etc/passwd`).
   */
  private fun isPathWithinSandbox(target: File): Boolean {
    return target.canonicalFile.startsWith(root.canonicalFile)
  }

  // ── Write ────────────────────────────────────────────────────

  /**
   * Writes [content] to a file at the given [relativePath] inside the sandbox.
   *
   * Automatically creates any missing intermediate directories. Returns `true` on
   * success or `false` if the extension is not allowed, the resolved path escapes
   * the sandbox, or an I/O error occurs.
   */
  fun writeCodeFile(relativePath: String, content: String): Boolean {
    if (!isAllowedExtension(relativePath)) {
      Log.w(TAG, "writeCodeFile: extension not allowed for '$relativePath'")
      return false
    }
    return try {
      val target = File(root, relativePath)
      if (!isPathWithinSandbox(target)) {
        Log.w(TAG, "writeCodeFile: path traversal blocked for '$relativePath'")
        return false
      }
      target.parentFile?.mkdirs()
      target.writeText(content, Charsets.UTF_8)
      Log.d(TAG, "writeCodeFile: wrote ${content.length} chars to '$relativePath'")
      true
    } catch (e: Exception) {
      Log.e(TAG, "writeCodeFile: failed for '$relativePath'", e)
      false
    }
  }

  // ── Read ─────────────────────────────────────────────────────

  /**
   * Reads and returns the contents of the file at [relativePath], or `null` if the
   * file does not exist, is a directory, escapes the sandbox, or cannot be read.
   */
  fun readCodeFile(relativePath: String): String? {
    return try {
      val target = File(root, relativePath)
      if (!isPathWithinSandbox(target)) {
        Log.w(TAG, "readCodeFile: path traversal blocked for '$relativePath'")
        return null
      }
      if (!target.exists() || target.isDirectory) null else target.readText(Charsets.UTF_8)
    } catch (e: Exception) {
      Log.e(TAG, "readCodeFile: failed for '$relativePath'", e)
      null
    }
  }

  // ── Delete ───────────────────────────────────────────────────

  /** Deletes a single file. Returns `true` if the file existed and was deleted. */
  fun deleteFile(relativePath: String): Boolean {
    val target = File(root, relativePath)
    if (!isPathWithinSandbox(target)) {
      Log.w(TAG, "deleteFile: path traversal blocked for '$relativePath'")
      return false
    }
    return target.exists() && target.isFile && target.delete()
  }

  // ── Listing ──────────────────────────────────────────────────

  /**
   * Returns a flat list of all file paths (relative to [root]) in the sandbox,
   * sorted alphabetically.
   */
  fun listAllFiles(): List<String> {
    val files = mutableListOf<String>()
    root.walkTopDown().filter { it.isFile }.forEach { file ->
      files.add(file.relativeTo(root).path)
    }
    return files.sorted()
  }

  /**
   * Returns a recursive tree representation of [dir] suitable for display.
   * Each entry is a [FileNode].
   */
  fun getFileTree(dir: File = root): FileNode {
    val children = (dir.listFiles() ?: emptyArray())
      .sortedWith(compareBy<File> { !it.isDirectory }.thenBy { it.name })
      .map { child ->
        if (child.isDirectory) getFileTree(child)
        else FileNode(name = child.name, relativePath = child.relativeTo(root).path, isDirectory = false)
      }
    return FileNode(
      name = dir.name,
      relativePath = if (dir == root) "" else dir.relativeTo(root).path,
      isDirectory = true,
      children = children,
    )
  }

  // ── Workspace Map (JSON) ────────────────────────────────────

  /**
   * Returns a clean JSON string representing the current file/folder tree
   * of the sandbox. Used by the AI's `Workspace_Map` skill to orient itself.
   */
  fun workspaceMapJson(): String {
    return fileNodeToJson(getFileTree()).toString(2)
  }

  private fun fileNodeToJson(node: FileNode): JSONObject {
    val obj = JSONObject()
    obj.put("name", node.name)
    obj.put("path", node.relativePath)
    obj.put("type", if (node.isDirectory) "directory" else "file")
    if (node.isDirectory && node.children.isNotEmpty()) {
      val arr = JSONArray()
      for (child in node.children) {
        arr.put(fileNodeToJson(child))
      }
      obj.put("children", arr)
    }
    return obj
  }
}

/**
 * A single node in the file tree shown in the FILE_BOX UI.
 */
data class FileNode(
  val name: String,
  val relativePath: String,
  val isDirectory: Boolean,
  val children: List<FileNode> = emptyList(),
)
