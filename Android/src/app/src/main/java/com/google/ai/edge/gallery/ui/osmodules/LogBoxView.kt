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
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.ai.edge.gallery.data.LogBoxManager
import com.google.ai.edge.gallery.ui.theme.absoluteBlack
import com.google.ai.edge.gallery.ui.theme.neonGreen

/**
 * LOG_BOX — a live, on-device diagnostic viewer that intercepts the Android
 * Logcat stream in real-time.
 *
 * **Aesthetic Enforcement:**
 * - Background: absolute black (`#000000`)
 * - Text: neon green (`#4ade80`), Monospace, 10.sp
 * - Zero vignette, zero rounded corners, edge-to-edge rendering
 * - Copy button: neon green icon on absolute black, zero drop shadow
 */
@Composable
fun LogBoxView(
  logBoxManager: LogBoxManager,
  innerPadding: PaddingValues,
  modifier: Modifier = Modifier,
) {
  val logLines by logBoxManager.logLines.collectAsState()
  val listState = rememberLazyListState()

  // Start the logcat stream when the view enters composition;
  // stop it when the view leaves to save CPU cycles.
  DisposableEffect(Unit) {
    logBoxManager.startStream()
    onDispose {
      logBoxManager.stopStream()
    }
  }

  // Auto-scroll to the bottom when new lines arrive.
  LaunchedEffect(logLines.size) {
    if (logLines.isNotEmpty()) {
      listState.animateScrollToItem(logLines.size - 1)
    }
  }

  Box(
    modifier = modifier
      .fillMaxSize()
      .background(absoluteBlack)
      .padding(innerPadding)
  ) {
    // ── Log stream ────────────────────────────────────────────
    LazyColumn(
      state = listState,
      modifier = Modifier
        .fillMaxSize()
        .padding(bottom = 48.dp),  // Room for the button row
      contentPadding = PaddingValues(horizontal = 4.dp, vertical = 4.dp),
    ) {
      itemsIndexed(logLines) { _, line ->
        Text(
          text = line,
          color = neonGreen,
          fontFamily = FontFamily.Monospace,
          fontSize = 10.sp,
          lineHeight = 13.sp,
          modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 1.dp),
        )
      }
    }

    // ── Action buttons (bottom-end) ───────────────────────────
    Box(
      modifier = Modifier
        .align(Alignment.BottomEnd)
        .padding(8.dp)
    ) {
      // Copy to clipboard button.
      IconButton(
        onClick = { logBoxManager.copyToClipboard() },
        colors = IconButtonDefaults.iconButtonColors(
          containerColor = absoluteBlack,
        ),
        modifier = Modifier.padding(end = 48.dp),
      ) {
        Icon(
          imageVector = Icons.Rounded.ContentCopy,
          contentDescription = "Copy telemetry to clipboard",
          tint = neonGreen,
        )
      }

      // Clear logs button.
      IconButton(
        onClick = { logBoxManager.clearLogs() },
        colors = IconButtonDefaults.iconButtonColors(
          containerColor = absoluteBlack,
        ),
        modifier = Modifier.align(Alignment.CenterEnd),
      ) {
        Icon(
          imageVector = Icons.Rounded.DeleteSweep,
          contentDescription = "Clear log buffer",
          tint = Color(0xFF808080),
        )
      }
    }
  }
}
