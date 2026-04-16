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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.ai.edge.gallery.data.LineSource
import com.google.ai.edge.gallery.data.TerminalLine
import com.google.ai.edge.gallery.data.TerminalSessionManager
import com.google.ai.edge.gallery.ui.theme.absoluteBlack
import com.google.ai.edge.gallery.ui.theme.neonGreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

// ── Colour map for terminal output sources ──────────────────────────────
private val stdinColor = Color(0xFF8BE9FD)    // cyan — echoed user input
private val stdoutColor = neonGreen            // green — standard output
private val stderrColor = Color(0xFFFF5555)    // red — errors
private val systemColor = Color(0xFFBD93F9)    // purple — system messages

/**
 * MSTR_CTRL — full-screen terminal UI backed by [TerminalSessionManager].
 *
 * UI rules:
 * • Pure black background, neon green text.
 * • Scrollable output area with colour-coded lines.
 * • Bottom input row for manual user commands.
 */
@Composable
fun MstrCtrlScreen(sessionManager: TerminalSessionManager) {
  val outputLines by sessionManager.outputLines.collectAsState()
  var inputText by remember { mutableStateOf("") }
  val listState = rememberLazyListState()
  val scope = rememberCoroutineScope()

  // Start the session on first composition.
  LaunchedEffect(Unit) {
    sessionManager.startSession()
  }

  // Auto-scroll to bottom when new lines arrive.
  LaunchedEffect(outputLines.size) {
    if (outputLines.isNotEmpty()) {
      listState.animateScrollToItem(outputLines.size - 1)
    }
  }

  Column(
    modifier = Modifier
      .fillMaxSize()
      .background(absoluteBlack),
  ) {
    // ── Output area ──────────────────────────────────────────
    LazyColumn(
      state = listState,
      modifier = Modifier
        .weight(1f)
        .fillMaxWidth()
        .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
      items(outputLines) { line ->
        TerminalOutputLine(line)
      }
    }

    // ── Bottom input row ─────────────────────────────────────
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .background(Color(0xFF0D0D0D))
        .padding(horizontal = 8.dp, vertical = 6.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Text(
        "$",
        color = neonGreen,
        fontFamily = FontFamily.Monospace,
        fontSize = 14.sp,
        modifier = Modifier.padding(end = 6.dp),
      )

      BasicTextField(
        value = inputText,
        onValueChange = { inputText = it },
        modifier = Modifier
          .weight(1f)
          .padding(vertical = 4.dp),
        textStyle = TextStyle(
          fontFamily = FontFamily.Monospace,
          fontSize = 13.sp,
          color = neonGreen,
        ),
        cursorBrush = SolidColor(neonGreen),
        singleLine = true,
      )

      Spacer(Modifier.width(4.dp))

      // Send button.
      IconButton(
        onClick = {
          val cmd = inputText.trim()
          if (cmd.isNotEmpty()) {
            inputText = ""
            scope.launch(Dispatchers.IO) {
              sessionManager.sendCommand(cmd, visible = true)
            }
          }
        },
      ) {
        Icon(
          Icons.AutoMirrored.Filled.Send,
          contentDescription = "Execute",
          tint = neonGreen,
        )
      }

      // Clear output button.
      IconButton(onClick = { sessionManager.clearOutput() }) {
        Icon(
          Icons.Default.DeleteSweep,
          contentDescription = "Clear",
          tint = neonGreen.copy(alpha = 0.6f),
        )
      }
    }
  }
}

// ── Single terminal line composable ─────────────────────────────────────

@Composable
private fun TerminalOutputLine(line: TerminalLine) {
  val color = when (line.source) {
    LineSource.STDIN -> stdinColor
    LineSource.STDOUT -> stdoutColor
    LineSource.STDERR -> stderrColor
    LineSource.SYSTEM -> systemColor
  }
  Text(
    text = line.text,
    color = color,
    fontFamily = FontFamily.Monospace,
    fontSize = 12.sp,
    modifier = Modifier
      .fillMaxWidth()
      .padding(vertical = 1.dp),
  )
}
