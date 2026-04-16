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
import android.util.Log
import java.io.File

private const val TAG = "OracleManager"

/**
 * Maximum number of tokens (approximate word-count proxy) returned from a
 * search result. Keeps context windows lean for the local LLM.
 */
private const val TOKEN_CAP = 1500

/**
 * Manages offline .zim archive search for the ORACLE_BOX module.
 *
 * Architecture:
 * - .zim files are placed under `<filesDir>/oracle_archives/`.
 * - This manager scans available archives and performs keyword search
 *   against them, returning token-capped Markdown results.
 *
 * Note: Full Kiwix .zim reader integration requires native libzim.
 * This implementation provides the Kotlin interface contract and a
 * graceful plaintext fallback for development/testing without native libs.
 */
class OracleManager(context: Context) {

  /** Directory where .zim archives are stored. */
  val archiveDir: File = File(context.filesDir, "oracle_archives").also { it.mkdirs() }

  /**
   * Lists all available .zim archive files.
   */
  fun listArchives(): List<String> {
    return (archiveDir.listFiles() ?: emptyArray())
      .filter { it.isFile && it.extension.equals("zim", ignoreCase = true) }
      .map { it.name }
      .sorted()
  }

  /**
   * Searches offline .zim archives for [query] and returns a token-capped
   * Markdown result suitable for injection into the LLM context.
   *
   * When native libzim is available, this will perform full-text search
   * across all loaded archives. Until then, it searches any `.md` or `.txt`
   * reference files placed alongside the .zim archives as a development
   * fallback.
   *
   * @param query  The search term or question.
   * @return Token-capped Markdown string, or a "no results" message.
   */
  fun search(query: String): String {
    Log.d(TAG, "search: query='$query'")

    val archives = listArchives()

    // Phase 1: Search plaintext/markdown fallback files in the archive dir.
    val fallbackResults = searchFallbackFiles(query)
    if (fallbackResults.isNotEmpty()) {
      return capTokens(fallbackResults)
    }

    // Phase 2: If .zim archives exist, note them but explain native search
    // is pending integration.
    if (archives.isNotEmpty()) {
      return capTokens(
        "## Oracle Search: \"$query\"\n\n" +
          "Found ${archives.size} .zim archive(s): ${archives.joinToString(", ")}\n\n" +
          "_Native .zim full-text search requires libzim integration. " +
          "Place `.md` or `.txt` reference files in `oracle_archives/` " +
          "for immediate keyword search._"
      )
    }

    return "## Oracle Search: \"$query\"\n\nNo archives found. " +
      "Place .zim files in the `oracle_archives/` directory to enable offline search."
  }

  /**
   * Searches `.md` and `.txt` files in [archiveDir] for lines containing [query].
   * Returns matching excerpts as a Markdown block.
   */
  private fun searchFallbackFiles(query: String): String {
    val queryLower = query.lowercase()
    val matches = mutableListOf<String>()

    val textFiles = (archiveDir.listFiles() ?: emptyArray())
      .filter { it.isFile && it.extension.lowercase() in setOf("md", "txt") }

    for (file in textFiles) {
      try {
        val lines = file.readLines()
        val matchingLines = lines.filter { it.lowercase().contains(queryLower) }
        if (matchingLines.isNotEmpty()) {
          matches.add("### ${file.name}\n${matchingLines.joinToString("\n")}")
        }
      } catch (e: Exception) {
        Log.w(TAG, "searchFallbackFiles: failed to read ${file.name}", e)
      }
    }

    return if (matches.isEmpty()) {
      ""
    } else {
      "## Oracle Search: \"$query\"\n\n${matches.joinToString("\n\n")}"
    }
  }

  /**
   * Caps the result text to approximately [TOKEN_CAP] tokens.
   * Uses a simple whitespace-split as a token approximation.
   */
  private fun capTokens(text: String): String {
    val words = text.split(Regex("\\s+"))
    if (words.size <= TOKEN_CAP) return text
    Log.d(TAG, "capTokens: trimming ${words.size} tokens to $TOKEN_CAP")
    return words.take(TOKEN_CAP).joinToString(" ") +
      "\n\n_[Result truncated to ~$TOKEN_CAP tokens]_"
  }
}
