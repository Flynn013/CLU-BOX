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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.google.ai.edge.gallery.data.FileBoxManager
import com.google.ai.edge.gallery.data.TerminalSessionManager
import com.google.ai.edge.gallery.ui.theme.absoluteBlack
import com.google.ai.edge.gallery.ui.theme.neonGreen

/**
 * COMMAND_CENTER — split-screen HUD combining FILE_BOX (top) and MSTR_CTRL (bottom).
 *
 * This gives the user a unified IDE-like view: code editor on top, interactive
 * terminal on the bottom. Both panels share the same `clu_file_box` sandbox so
 * file changes are immediately visible across both.
 */
@Composable
fun CommandCenterScreen(
  fileBoxManager: FileBoxManager,
  terminalSessionManager: TerminalSessionManager,
) {
  Column(
    modifier = Modifier
      .fillMaxSize()
      .background(absoluteBlack),
  ) {
    // ── Top half: FILE_BOX editor ─────────────────────────────
    Box(
      modifier = Modifier
        .weight(1f)
        .fillMaxWidth(),
    ) {
      FileBoxScreen(fileBoxManager = fileBoxManager)
    }

    // ── Divider ───────────────────────────────────────────────
    HorizontalDivider(
      modifier = Modifier.fillMaxWidth().height(2.dp),
      color = neonGreen.copy(alpha = 0.5f),
    )

    // ── Bottom half: MSTR_CTRL terminal ───────────────────────
    Box(
      modifier = Modifier
        .weight(1f)
        .fillMaxWidth(),
    ) {
      MstrCtrlScreen(sessionManager = terminalSessionManager)
    }
  }
}
