/*
 * Copyright 2026 Flynn013 / CLU/BOX
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package com.google.ai.edge.gallery.ui.osmodules

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.ai.edge.gallery.data.busybox.BusyBoxBridge
import com.google.ai.edge.gallery.ui.theme.absoluteBlack
import com.google.ai.edge.gallery.ui.theme.neonGreen
import com.google.ai.edge.gallery.ui.theme.terminalError
import com.google.ai.edge.gallery.ui.theme.terminalLightGrey
import com.google.ai.edge.gallery.ui.theme.terminalMidGrey
import com.google.ai.edge.gallery.ui.theme.terminalOnSurface
import com.google.ai.edge.gallery.ui.theme.terminalOutline
import kotlinx.coroutines.launch

/**
 * MSTR_CTRL — pure-native BusyBox terminal panel.
 *
 * Every command is evaluated by running `busybox sh -c <line>` as a sub-process;
 * output is streamed back into the scrolling buffer. No external app, no PTY, no IPC.
 */
@Composable
fun MstrCtrlScreen() {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  val keyboard = LocalSoftwareKeyboardController.current

  val lines = remember { mutableStateListOf<TermLine>() }
  val listState = rememberLazyListState()
  var input by remember { mutableStateOf("") }
  var running by remember { mutableStateOf(false) }
  var ready by remember { mutableStateOf(false) }

  LaunchedEffect(Unit) {
    val installed = BusyBoxBridge.ensureInstalled(context)
    if (installed != null) {
      lines += TermLine.system("[CLU/BOX] BusyBox armed at $installed")
    } else {
      lines += TermLine.system("[CLU/BOX] BusyBox asset not bundled — using /system/bin/sh fallback")
      lines += TermLine.system("         (add busybox-arm64-v8a to assets/busybox/ for full capability)")
    }
    val uname = BusyBoxBridge.shell(context, "uname -a")
    if (uname.exitCode == 0) {
      lines += TermLine.system(uname.stdout.trim())
    } else {
      lines += TermLine.stderr(uname.stderr.trim().ifBlank { "(uname unavailable)" })
    }
    ready = true
  }

  LaunchedEffect(lines.size) {
    if (lines.isNotEmpty()) listState.scrollToItem(lines.lastIndex)
  }

  Column(
    modifier = Modifier
      .fillMaxSize()
      .background(absoluteBlack)
      .imePadding(),
  ) {
    // ── Status bar ────────────────────────────────────────────────────
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .background(absoluteBlack)
        .padding(horizontal = 12.dp, vertical = 6.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      val statusColor = if (ready) neonGreen else terminalOnSurface.copy(alpha = 0.5f)
      Box(
        modifier = Modifier
          .width(8.dp)
          .height(8.dp)
          .clip(RoundedCornerShape(50))
          .background(statusColor),
      )
      Spacer(Modifier.width(8.dp))
      Text(
        text = if (ready) "MSTR_CTRL · BUSYBOX READY" else "MSTR_CTRL · BOOTSTRAPPING…",
        color = statusColor,
        fontFamily = FontFamily.Monospace,
        fontSize = 12.sp,
      )
      Spacer(Modifier.weight(1f))
      IconButton(onClick = {
        lines.clear()
        scope.launch {
          val p = BusyBoxBridge.ensureInstalled(context)
          lines += TermLine.system(
            if (p != null) "[CLU/BOX] terminal cleared · BusyBox ready"
            else "[CLU/BOX] terminal cleared · system shell mode"
          )
        }
      }) {
        Icon(Icons.Filled.Refresh, contentDescription = "clear", tint = neonGreen)
      }
    }

    // ── Scroll-back buffer ────────────────────────────────────────────
    Box(
      modifier = Modifier
        .fillMaxWidth()
        .weight(1f)
        .padding(horizontal = 8.dp)
        .background(terminalMidGrey)
        .border(1.dp, terminalOutline),
    ) {
      LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize().padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
      ) {
        items(lines) { line ->
          Text(
            text = line.text,
            color = line.color,
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
          )
        }
      }
    }

    // ── Input line ────────────────────────────────────────────────────
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .background(terminalLightGrey)
        .padding(horizontal = 12.dp, vertical = 8.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Text(
        text = "❯",
        color = neonGreen,
        fontFamily = FontFamily.Monospace,
        fontSize = 14.sp,
      )
      Spacer(Modifier.width(8.dp))
      BasicTextField(
        value = input,
        onValueChange = { input = it },
        textStyle = TextStyle(
          color = terminalOnSurface,
          fontFamily = FontFamily.Monospace,
          fontSize = 13.sp,
        ),
        cursorBrush = SolidColor(neonGreen),
        singleLine = true,
        keyboardOptions = KeyboardOptions(
          imeAction = ImeAction.Send,
          keyboardType = KeyboardType.Ascii,
        ),
        keyboardActions = KeyboardActions(
          onSend = {
            val cmd = input.trim()
            if (cmd.isNotEmpty() && !running) {
              input = ""
              keyboard?.hide()
              runTerminalCommand(cmd, scope, lines, context, onStart = { running = true }, onDone = { running = false })
            }
          },
        ),
        modifier = Modifier.weight(1f),
      )
      IconButton(onClick = {
        val cmd = input.trim()
        if (cmd.isNotEmpty() && !running) {
          input = ""
          runTerminalCommand(cmd, scope, lines, context, onStart = { running = true }, onDone = { running = false })
        }
      }) {
        Icon(Icons.Filled.PlayArrow, contentDescription = "run", tint = neonGreen)
      }
    }
  }
}

private fun runTerminalCommand(
  cmd: String,
  scope: kotlinx.coroutines.CoroutineScope,
  lines: androidx.compose.runtime.snapshots.SnapshotStateList<TermLine>,
  context: android.content.Context,
  onStart: () -> Unit,
  onDone: () -> Unit,
) {
  onStart()
  lines += TermLine.command(cmd)
  scope.launch {
    val r = BusyBoxBridge.shell(context, cmd)
    if (r.stdout.isNotBlank()) lines += TermLine.stdout(r.stdout.trimEnd())
    if (r.stderr.isNotBlank()) lines += TermLine.stderr(r.stderr.trimEnd())
    lines += TermLine.system("[exit ${r.exitCode} · ${r.durationMs}ms]")
    onDone()
  }
}

private data class TermLine(val text: String, val color: Color) {
  companion object {
    fun command(s: String) = TermLine("❯ $s", neonGreen)
    fun stdout(s: String) = TermLine(s, terminalOnSurface)
    fun stderr(s: String) = TermLine(s, terminalError)
    fun system(s: String) = TermLine(s, terminalOnSurface.copy(alpha = 0.55f))
    fun error(s: String) = TermLine(s, terminalError)
  }
}
