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

package com.google.ai.edge.gallery.ui.common.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Chat
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.google.ai.edge.gallery.data.brainbox.ChatHistoryDao
import com.google.ai.edge.gallery.data.brainbox.ChatSessionSummary
import com.google.ai.edge.gallery.ui.theme.neonGreen
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * A bottom sheet showing all persisted chat sessions grouped by (taskId, modelName).
 *
 * Tapping a session row invokes [onSessionSelected] so the caller can navigate to that
 * model/task pair and reload the history. The delete button wipes that specific session.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatHistorySheet(
  chatHistoryDao: ChatHistoryDao,
  onDismiss: () -> Unit,
  onSessionSelected: (taskId: String, modelName: String) -> Unit,
  onSessionDeleted: (taskId: String, modelName: String) -> Unit,
) {
  val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
  var sessions by remember { mutableStateOf<List<ChatSessionSummary>>(emptyList()) }

  LaunchedEffect(Unit) {
    sessions = chatHistoryDao.getSessions()
  }

  ModalBottomSheet(
    onDismissRequest = onDismiss,
    sheetState = sheetState,
  ) {
    Column(modifier = Modifier.padding(horizontal = 16.dp)) {
      Text(
        "CHAT HISTORY",
        style = MaterialTheme.typography.titleMedium,
        color = neonGreen,
        fontFamily = FontFamily.Monospace,
        modifier = Modifier.padding(bottom = 4.dp),
      )
      Text(
        "Previous conversations stored on device",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontFamily = FontFamily.Monospace,
      )
      Spacer(Modifier.height(12.dp))

      if (sessions.isEmpty()) {
        Text(
          "No chat history yet.\nStart chatting in CHAT_BOX to create sessions.",
          color = neonGreen.copy(alpha = 0.5f),
          fontFamily = FontFamily.Monospace,
          style = MaterialTheme.typography.bodyMedium,
          modifier = Modifier.padding(vertical = 24.dp),
        )
      } else {
        LazyColumn(verticalArrangement = Arrangement.spacedBy(0.dp)) {
          items(sessions, key = { "${it.taskId}::${it.modelName}" }) { session ->
            SessionRow(
              session = session,
              onClick = { onSessionSelected(session.taskId, session.modelName) },
              onDelete = { onSessionDeleted(session.taskId, session.modelName) },
            )
            HorizontalDivider(color = neonGreen.copy(alpha = 0.15f))
          }
        }
      }
      Spacer(Modifier.height(24.dp))
    }
  }
}

@Composable
private fun SessionRow(
  session: ChatSessionSummary,
  onClick: () -> Unit,
  onDelete: () -> Unit,
) {
  val dateFormat = remember { SimpleDateFormat("MMM dd, HH:mm", Locale.getDefault()) }
  val dateStr = remember(session.lastTimestampMs) {
    dateFormat.format(Date(session.lastTimestampMs))
  }
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .clickable(onClick = onClick)
      .padding(vertical = 12.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Icon(
      Icons.Outlined.Chat,
      contentDescription = null,
      tint = neonGreen,
      modifier = Modifier.size(20.dp),
    )
    Spacer(Modifier.width(12.dp))
    Column(modifier = Modifier.weight(1f)) {
      Text(
        session.modelName,
        style = MaterialTheme.typography.titleSmall,
        fontFamily = FontFamily.Monospace,
        color = neonGreen,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
      Text(
        "${session.messageCount} messages · $dateStr",
        style = MaterialTheme.typography.bodySmall,
        fontFamily = FontFamily.Monospace,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
    IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
      Icon(
        Icons.Outlined.DeleteOutline,
        contentDescription = "Delete session",
        tint = MaterialTheme.colorScheme.error,
        modifier = Modifier.size(18.dp),
      )
    }
  }
}
