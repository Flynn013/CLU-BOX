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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.ai.edge.gallery.data.TerminalSessionManager
import com.google.ai.edge.gallery.ui.theme.absoluteBlack
import com.google.ai.edge.gallery.ui.theme.neonGreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// ── Diff line colours (Zen-Command theme) ───────────────────────────────
private val addedColor = Color(0xFF4ADE80)      // neon green — added lines
private val removedColor = Color(0xFFFF5555)     // red — removed lines
private val contextColor = Color(0xFF6B7280)     // grey — context lines
private val headerColor = Color(0xFFBD93F9)      // purple — diff headers
private val defaultColor = neonGreen

/**
 * DIFF_BOX — Side-by-side code diff viewer in Zen-Command aesthetic.
 *
 * Runs `git diff` via the [TerminalSessionManager] and renders the output
 * with colour-coded lines:
 * - Green (+) added lines
 * - Red (-) removed lines
 * - Purple (@@) diff headers
 * - Grey (context) unchanged lines
 *
 * A refresh button re-runs the diff on demand.
 */
@Composable
fun DiffBoxScreen(sessionManager: TerminalSessionManager) {
  var diffOutput by remember { mutableStateOf<List<DiffLine>>(emptyList()) }
  var isLoading by remember { mutableStateOf(true) }
  val scope = rememberCoroutineScope()

  // Load diff on first composition.
  LaunchedEffect(Unit) {
    diffOutput = loadGitDiff(sessionManager)
    isLoading = false
  }

  Column(
    modifier = Modifier
      .fillMaxSize()
      .background(absoluteBlack),
  ) {
    // ── Header row with refresh ────────────────────────────────
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .background(Color(0xFF0D0D0D))
        .padding(horizontal = 12.dp, vertical = 8.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Text(
        "git diff",
        color = neonGreen,
        fontFamily = FontFamily.Monospace,
        fontSize = 14.sp,
        modifier = Modifier.weight(1f),
      )

      IconButton(
        onClick = {
          isLoading = true
          scope.launch {
            diffOutput = loadGitDiff(sessionManager)
            isLoading = false
          }
        },
      ) {
        Icon(
          Icons.Default.Refresh,
          contentDescription = "Refresh diff",
          tint = neonGreen,
        )
      }
    }

    // ── Diff content ───────────────────────────────────────────
    if (isLoading) {
      Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
      ) {
        Text(
          "Loading diff…",
          color = neonGreen.copy(alpha = 0.6f),
          fontFamily = FontFamily.Monospace,
          fontSize = 13.sp,
        )
      }
    } else if (diffOutput.isEmpty()) {
      Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
      ) {
        Text(
          "No changes detected.\nRun 'git init' in FILE_BOX to enable diff tracking.",
          color = neonGreen.copy(alpha = 0.5f),
          fontFamily = FontFamily.Monospace,
          fontSize = 13.sp,
        )
      }
    } else {
      LazyColumn(
        modifier = Modifier
          .fillMaxSize()
          .padding(horizontal = 4.dp, vertical = 4.dp),
      ) {
        items(diffOutput) { line ->
          DiffLineRow(line)
        }
      }
    }
  }
}

// ── Data model ──────────────────────────────────────────────────────────

private data class DiffLine(
  val text: String,
  val type: DiffLineType,
)

private enum class DiffLineType { ADDED, REMOVED, CONTEXT, HEADER, META }

// ── Single diff line composable ─────────────────────────────────────────

@Composable
private fun DiffLineRow(line: DiffLine) {
  val color = when (line.type) {
    DiffLineType.ADDED -> addedColor
    DiffLineType.REMOVED -> removedColor
    DiffLineType.HEADER -> headerColor
    DiffLineType.CONTEXT -> contextColor
    DiffLineType.META -> headerColor
  }
  val prefix = when (line.type) {
    DiffLineType.ADDED -> "+"
    DiffLineType.REMOVED -> "-"
    DiffLineType.HEADER -> ""
    DiffLineType.CONTEXT -> " "
    DiffLineType.META -> ""
  }

  Row(
    modifier = Modifier
      .fillMaxWidth()
      .horizontalScroll(rememberScrollState())
      .padding(vertical = 1.dp),
  ) {
    // Line number gutter placeholder (for alignment).
    Text(
      text = prefix,
      color = color.copy(alpha = 0.5f),
      fontFamily = FontFamily.Monospace,
      fontSize = 11.sp,
      modifier = Modifier.width(16.dp),
    )

    Spacer(Modifier.width(4.dp))

    Text(
      text = line.text,
      color = color,
      fontFamily = FontFamily.Monospace,
      fontSize = 12.sp,
    )
  }
}

// ── Diff loading helper ─────────────────────────────────────────────────

private suspend fun loadGitDiff(sessionManager: TerminalSessionManager): List<DiffLine> {
  return withContext(Dispatchers.IO) {
    val raw = sessionManager.sendCommand("git diff 2>&1", visible = false)
    if (raw.isBlank() || raw == "(no output)" || raw.contains("not a git repository")) {
      emptyList()
    } else {
      parseDiffOutput(raw)
    }
  }
}

/**
 * Parses raw `git diff` output into typed [DiffLine] entries.
 */
private fun parseDiffOutput(raw: String): List<DiffLine> {
  return raw.lines().map { line ->
    when {
      line.startsWith("+++") || line.startsWith("---") ->
        DiffLine(line, DiffLineType.META)
      line.startsWith("@@") ->
        DiffLine(line, DiffLineType.HEADER)
      line.startsWith("+") ->
        DiffLine(line.removePrefix("+"), DiffLineType.ADDED)
      line.startsWith("-") ->
        DiffLine(line.removePrefix("-"), DiffLineType.REMOVED)
      line.startsWith("diff ") || line.startsWith("index ") ->
        DiffLine(line, DiffLineType.META)
      else ->
        DiffLine(line, DiffLineType.CONTEXT)
    }
  }
}
