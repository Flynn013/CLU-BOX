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

package com.google.ai.edge.gallery.data.brainbox

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.UUID

private const val TAG = "BrainBoxIO"

// ── Delimiter constants used for the strict Markdown I/O format ──────────
private const val NEURON_START = "===NEURON_START==="
private const val NEURON_END = "===NEURON_END==="
private const val PAYLOAD_SEPARATOR = "---PAYLOAD---"

// ═══════════════════════════════════════════════════════════════════════════
// Legacy JSON Export/Import (kept for backward compatibility)
// ═══════════════════════════════════════════════════════════════════════════

/**
 * Serializes all [NeuronEntity] rows into a raw JSON string.
 */
suspend fun exportBrain(dao: BrainBoxDao): String {
  val neurons = dao.getAllNeurons()
  return Gson().toJson(neurons)
}

/**
 * Parses [json] and **overwrites** the BrainBox database with the deserialized neurons.
 */
suspend fun importBrain(dao: BrainBoxDao, json: String) {
  val type = object : TypeToken<List<NeuronEntity>>() {}.type
  val neurons: List<NeuronEntity> = Gson().fromJson(json, type)
  dao.deleteAllNeurons()
  neurons.forEach { dao.insertNeuron(it) }
}

// ═══════════════════════════════════════════════════════════════════════════
// Phase 2 – Markdown Exporter (RLHF Review Mode)
// ═══════════════════════════════════════════════════════════════════════════

/**
 * Exports neurons from the BrainBox into the strict Markdown review format.
 *
 * @param dao           The BrainBox DAO.
 * @param includeCore   When `true`, both Core and Malleable neurons are exported.
 *                      When `false`, only Malleable neurons are exported.
 * @return The full Markdown string.
 */
suspend fun exportBrainToMarkdown(dao: BrainBoxDao, includeCore: Boolean): String {
  val neurons = if (includeCore) dao.getAllNeurons() else dao.getMalleableNeurons()
  return buildString {
    for (neuron in neurons) {
      appendLine(NEURON_START)
      appendLine("ID: ${neuron.id}")
      appendLine("LABEL: ${neuron.label}")
      appendLine("TYPE: ${neuron.type}")
      appendLine("IS_CORE: ${neuron.isCore}")
      appendLine(PAYLOAD_SEPARATOR)
      appendLine(neuron.content)
      appendLine(NEURON_END)
      appendLine()  // blank line between entries for readability
    }
  }.trimEnd()
}

/**
 * Writes the Markdown review string to `CLU_BRAIN_REVIEW.md` in the user's
 * public Downloads folder via MediaStore.
 *
 * @return `true` on success, `false` on failure.
 */
fun saveBrainMarkdownToDownloads(context: Context, markdown: String): Boolean {
  val fileName = "CLU_BRAIN_REVIEW.md"
  val resolver = context.contentResolver
  val values = ContentValues().apply {
    put(MediaStore.Downloads.DISPLAY_NAME, fileName)
    put(MediaStore.Downloads.MIME_TYPE, "text/markdown")
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
      put(MediaStore.Downloads.IS_PENDING, 1)
    }
  }
  val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
  if (uri == null) {
    Log.e(TAG, "saveBrainMarkdownToDownloads: could not create file in Downloads")
    return false
  }
  return try {
    resolver.openOutputStream(uri)?.use { it.write(markdown.toByteArray()) }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
      values.clear()
      values.put(MediaStore.Downloads.IS_PENDING, 0)
      resolver.update(uri, values, null, null)
    }
    Log.d(TAG, "saveBrainMarkdownToDownloads: saved $fileName (${markdown.length} chars)")
    true
  } catch (e: Exception) {
    Log.e(TAG, "saveBrainMarkdownToDownloads: write failed", e)
    false
  }
}

// ═══════════════════════════════════════════════════════════════════════════
// Phase 3 – Markdown Importer (Slicing the Brain)
// ═══════════════════════════════════════════════════════════════════════════

/**
 * Reads a Markdown file from the given [uri], parses it using the
 * `===NEURON_START===` / `===NEURON_END===` delimiter format, and upserts
 * each neuron into the BrainBox database.
 *
 * - If a neuron ID already exists in the DB, its record is **updated**.
 * - If the ID is new (e.g. the user manually added a block in the text file),
 *   it is **inserted** as a new record.
 *
 * @return The number of neurons processed.
 */
suspend fun importBrainFromMarkdown(context: Context, dao: BrainBoxDao, uri: Uri): Int {
  val text = context.contentResolver.openInputStream(uri)?.use { stream ->
    BufferedReader(InputStreamReader(stream)).readText()
  } ?: throw IllegalArgumentException("Could not read file from uri: $uri")

  return importBrainFromMarkdownText(dao, text)
}

/**
 * Core parsing logic separated from Android I/O for testability.
 */
suspend fun importBrainFromMarkdownText(dao: BrainBoxDao, text: String): Int {
  // Split into blocks between NEURON_START and NEURON_END.
  val blocks = mutableListOf<String>()
  var remaining = text
  while (true) {
    val startIdx = remaining.indexOf(NEURON_START)
    if (startIdx == -1) break
    val endIdx = remaining.indexOf(NEURON_END, startIdx)
    if (endIdx == -1) break
    val block = remaining.substring(startIdx + NEURON_START.length, endIdx).trim()
    blocks.add(block)
    remaining = remaining.substring(endIdx + NEURON_END.length)
  }

  val existingIds = dao.getAllNeurons().map { it.id }.toSet()
  var count = 0

  for (block in blocks) {
    val payloadIdx = block.indexOf(PAYLOAD_SEPARATOR)
    if (payloadIdx == -1) {
      Log.w(TAG, "importBrainFromMarkdown: block missing $PAYLOAD_SEPARATOR — skipping")
      continue
    }

    val headerSection = block.substring(0, payloadIdx).trim()
    val payload = block.substring(payloadIdx + PAYLOAD_SEPARATOR.length).trim()

    // Parse header fields.
    val headers = mutableMapOf<String, String>()
    for (line in headerSection.lines()) {
      val colonIdx = line.indexOf(':')
      if (colonIdx > 0) {
        val key = line.substring(0, colonIdx).trim().uppercase()
        val value = line.substring(colonIdx + 1).trim()
        headers[key] = value
      }
    }

    val id = headers["ID"]?.ifBlank { null } ?: UUID.randomUUID().toString()
    val label = headers["LABEL"] ?: "Imported"
    val type = headers["TYPE"] ?: "Imported"
    val isCore = headers["IS_CORE"]?.lowercase() == "true"

    val neuron = NeuronEntity(
      id = id,
      label = label,
      type = type,
      content = payload,
      synapses = "",  // Synapses are embedded in the payload as [[Wiki-Links]].
      isCore = isCore,
    )

    if (id in existingIds) {
      dao.updateNeuron(neuron)
    } else {
      dao.insertNeuron(neuron)
    }
    count++
  }

  Log.d(TAG, "importBrainFromMarkdown: processed $count neuron(s)")
  return count
}
