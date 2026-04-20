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

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import java.io.File
import java.io.FileInputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

private const val TAG = "ZipExporter"

/**
 * Zips the [sourceDir] and writes the resulting archive to the public Downloads folder
 * using [MediaStore] (Android 10+) or direct file access on older devices.
 *
 * Returns the display name of the written file, or `null` on failure.
 */
fun exportDirectoryAsZip(context: Context, sourceDir: File, zipFileName: String): String? {
  if (!sourceDir.exists() || !sourceDir.isDirectory) {
    Log.w(TAG, "exportDirectoryAsZip: source does not exist or is not a directory")
    return null
  }

  return try {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
      exportViaMediaStore(context, sourceDir, zipFileName)
    } else {
      exportViaDirectFile(sourceDir, zipFileName)
    }
  } catch (e: Exception) {
    Log.e(TAG, "exportDirectoryAsZip failed", e)
    null
  }
}

// ── MediaStore (API 29+) ────────────────────────────────────────

private fun exportViaMediaStore(context: Context, sourceDir: File, zipFileName: String): String? {
  val values = ContentValues().apply {
    put(MediaStore.Downloads.DISPLAY_NAME, zipFileName)
    put(MediaStore.Downloads.MIME_TYPE, "application/zip")
    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
    put(MediaStore.Downloads.IS_PENDING, 1)
  }
  val resolver = context.contentResolver
  val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return null

  resolver.openOutputStream(uri)?.use { outputStream ->
    ZipOutputStream(outputStream).use { zos ->
      addDirToZip(zos, sourceDir, sourceDir)
    }
  }

  values.clear()
  values.put(MediaStore.Downloads.IS_PENDING, 0)
  resolver.update(uri, values, null, null)

  Log.d(TAG, "Exported $zipFileName via MediaStore")
  return zipFileName
}

// ── Direct file (API <29) ───────────────────────────────────────

@Suppress("DEPRECATION")
private fun exportViaDirectFile(sourceDir: File, zipFileName: String): String? {
  val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
  val zipFile = File(downloads, zipFileName)
  ZipOutputStream(zipFile.outputStream()).use { zos ->
    addDirToZip(zos, sourceDir, sourceDir)
  }
  Log.d(TAG, "Exported $zipFileName via direct file")
  return zipFileName
}

// ── Shared helper ───────────────────────────────────────────────

private fun addDirToZip(zos: ZipOutputStream, fileToZip: File, root: File, depth: Int = 0) {
  if (depth > 50) {
    Log.w("ZipExporter", "addDirToZip: depth limit (50) reached at '${fileToZip.path}' — skipping")
    return
  }
  if (fileToZip.isDirectory) {
    val children = fileToZip.listFiles() ?: return
    for (child in children) {
      addDirToZip(zos, child, root, depth + 1)
    }
  } else {
    val entryName = fileToZip.relativeTo(root).path
    zos.putNextEntry(ZipEntry(entryName))
    FileInputStream(fileToZip).use { fis ->
      fis.copyTo(zos, bufferSize = 4096)
    }
    zos.closeEntry()
  }
}
