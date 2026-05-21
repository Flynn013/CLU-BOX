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

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Article
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.ai.edge.gallery.R
import com.google.ai.edge.gallery.ui.theme.neonGreen
import com.google.ai.edge.gallery.ui.theme.terminalLightGrey
import com.google.ai.edge.gallery.ui.theme.terminalMidGrey
import com.google.ai.edge.gallery.ui.theme.terminalOnSurface
import com.google.ai.edge.gallery.ui.theme.terminalOutline

private const val MAX_DESCRIPTION_LINES = 4

/** CLU/BOX-styled collapsable tool-call progress card. */
@Composable
fun MessageBodyCollapsableProgressPanel(message: ChatMessageCollapsableProgressPanel) {
  // Expansion state: auto-expand while running; if the user manually toggles
  // it while running, that choice is preserved until inProgress flips again.
  // Using `rememberSaveable` keyed on `inProgress` resets only when the task
  // starts/stops — not on every item-added recomposition.
  var isExpanded by rememberSaveable { mutableStateOf(true) }
  var showLogsViewer by remember { mutableStateOf(false) }

  val displayTitle = when {
    !message.inProgress && !isExpanded && message.items.isNotEmpty() -> {
      val n = message.items.size
      "Ran $n action${if (n == 1) "" else "s"}"
    }
    else -> message.title
  }

  Column(
    modifier = Modifier
      .fillMaxWidth()
      .clip(RoundedCornerShape(10.dp))
      .background(terminalMidGrey)
      .border(1.dp, terminalOutline, RoundedCornerShape(10.dp))
      .clickable { isExpanded = !isExpanded },
  ) {
    // ── Header ──────────────────────────────────────────────────────────────
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = 14.dp, vertical = 10.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.SpaceBetween,
    ) {
      Row(
        modifier = Modifier.weight(1f),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
      ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(20.dp)) {
          if (message.inProgress) {
            CircularProgressIndicator(
              modifier = Modifier.size(14.dp),
              strokeWidth = 2.dp,
              color = neonGreen,
            )
          } else {
            Icon(
              message.doneIcon,
              contentDescription = null,
              modifier = Modifier.size(18.dp),
              tint = neonGreen,
            )
          }
        }

        AnimatedContent(
          targetState = displayTitle,
          modifier = Modifier.weight(1f),
          transitionSpec = {
            slideInVertically { it } + fadeIn() togetherWith slideOutVertically { -it } + fadeOut()
          },
        ) { curTitle ->
          Text(
            text = curTitle,
            color = if (message.inProgress) neonGreen else terminalOnSurface,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
        }
      }

      Spacer(Modifier.width(8.dp))
      Icon(
        imageVector = if (isExpanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
        contentDescription = if (isExpanded) "Collapse" else "Expand",
        tint = neonGreen.copy(alpha = 0.7f),
        modifier = Modifier.size(18.dp),
      )
    }

    // ── Expandable content ───────────────────────────────────────────────────
    AnimatedVisibility(
      visible = isExpanded,
      enter = expandVertically(),
      exit = shrinkVertically(),
    ) {
      Column(
        modifier = Modifier
          .fillMaxWidth()
          .padding(horizontal = 12.dp)
          .padding(bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        message.items.forEach { item ->
          ToolCallItemRow(item)
        }

        if (message.logMessages.isNotEmpty()) {
          Spacer(Modifier.height(2.dp))
          Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
            AssistChip(
              onClick = { showLogsViewer = true },
              label = {
                Text(
                  text = stringResource(R.string.view_console_logs),
                  fontFamily = FontFamily.Monospace,
                  fontSize = 11.sp,
                )
              },
              leadingIcon = {
                Icon(
                  Icons.AutoMirrored.Outlined.Article,
                  contentDescription = null,
                  modifier = Modifier.size(AssistChipDefaults.IconSize),
                  tint = neonGreen,
                )
              },
              colors = AssistChipDefaults.assistChipColors(
                containerColor = Color.Transparent,
                labelColor = terminalOnSurface.copy(alpha = 0.7f),
              ),
              border = BorderStroke(1.dp, terminalLightGrey),
            )
          }
        }
      }
    }
  }

  if (showLogsViewer) {
    LogsViewer(logs = message.logMessages, onDismissRequest = { showLogsViewer = false })
  }
}

@Composable
private fun ToolCallItemRow(item: ProgressPanelItem) {
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .clip(RoundedCornerShape(8.dp))
      .background(terminalLightGrey.copy(alpha = 0.2f))
      .border(1.dp, terminalOutline.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
      .padding(horizontal = 10.dp, vertical = 8.dp),
    verticalAlignment = Alignment.Top,
    horizontalArrangement = Arrangement.spacedBy(10.dp),
  ) {
    // Status dot
    Box(
      modifier = Modifier
        .size(7.dp)
        .clip(CircleShape)
        .background(neonGreen)
        .padding(top = 5.dp),  // optical alignment with first text line
    )
    Column(modifier = Modifier.weight(1f)) {
      Text(
        text = item.title,
        color = terminalOnSurface,
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Bold,
        fontSize = 12.sp,
      )
      if (item.description.isNotEmpty()) {
        Spacer(Modifier.height(3.dp))
        // Use maxLines + ellipsis instead of nested verticalScroll (which breaks
        // height measurement inside LazyColumn and causes the card to not expand).
        Text(
          text = item.description,
          color = terminalOnSurface.copy(alpha = 0.6f),
          fontFamily = FontFamily.Monospace,
          fontSize = 11.sp,
          maxLines = MAX_DESCRIPTION_LINES,
          overflow = TextOverflow.Ellipsis,
        )
      }
    }
  }
}
