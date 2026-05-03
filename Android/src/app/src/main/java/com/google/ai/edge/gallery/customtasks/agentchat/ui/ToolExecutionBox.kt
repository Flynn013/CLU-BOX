/*
 * Copyright 2026 Flynn013 / CLU/BOX
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package com.google.ai.edge.gallery.customtasks.agentchat.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.animation.core.animateFloat
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.ai.edge.gallery.customtasks.agentchat.AgentGovernor
import com.google.ai.edge.gallery.ui.theme.customColors
import com.google.ai.edge.gallery.ui.theme.neonGreen
import com.google.ai.edge.gallery.ui.theme.terminalError
import com.google.ai.edge.gallery.ui.theme.terminalLightGrey
import com.google.ai.edge.gallery.ui.theme.terminalMidGrey
import com.google.ai.edge.gallery.ui.theme.terminalOnSurface
import com.google.ai.edge.gallery.ui.theme.terminalOutline

/**
 * Goose-style collapsible tool-execution panel.
 *
 * Renders a single [AgentGovernor.ToolExecution] as a compact, terminal-styled
 * card that the user can tap to expand. Mirrors the visual rhythm of Block's
 * Goose desktop client:
 *
 * ```
 * ┌──────────────────────────────────────────────────────────────────┐
 * │ ▶ tool ▸ shell        running…                       0.42s        │
 * └──────────────────────────────────────────────────────────────────┘
 *
 * ┌──────────────────────────────────────────────────────────────────┐
 * │ ✓ tool ▸ fileBoxRead  ok                              0.08s        │
 * │   args: { "relativePath": "notes.md" }                              │
 * │   result: ## Today\n* refactor governor                             │
 * └──────────────────────────────────────────────────────────────────┘
 * ```
 *
 * - Pure-black background, neon-green accent, terminal-grey outline.
 * - Args/result blocks use a monospace font for readability.
 * - Long results auto-truncate to 2 KB; the truncation happens upstream in
 *   [AgentGovernor.onToolResult] so this composable doesn't need to do any
 *   string manipulation of its own.
 */
@Composable
fun ToolExecutionBox(
  execution: AgentGovernor.ToolExecution,
  modifier: Modifier = Modifier,
) {
  var expanded by remember { mutableStateOf(false) }
  val rotation by animateFloatAsState(
    targetValue = if (expanded) 0f else -90f,
    label = "chevron-rotation",
  )

  // Continuous neon pulse while the tool is actively executing. The opacity
  // oscillates between 35 % and 100 % on a 900 ms triangle wave, which gives
  // the Goose-style "alive" feedback without distracting motion blur.
  val pulse by rememberInfiniteTransition(label = "tool-pulse").animateFloat(
    initialValue = 0.35f,
    targetValue = 1f,
    animationSpec = infiniteRepeatable(
      animation = tween(durationMillis = 900, easing = LinearEasing),
      repeatMode = RepeatMode.Reverse,
    ),
    label = "tool-pulse-alpha",
  )
  val isPulsing = execution.phase == AgentGovernor.Phase.EXECUTING_TOOL
  val borderAlpha = if (isPulsing) pulse else 1f

  // Status colour mapping mirrors Goose's pill semantics.
  val (statusColor, statusIcon, statusLabel) = when (execution.phase) {
    AgentGovernor.Phase.EXECUTING_TOOL -> Triple(neonGreen, Icons.Filled.PlayArrow, "running…")
    AgentGovernor.Phase.RESUMING_STREAM -> Triple(neonGreen, Icons.Filled.Check, "ok")
    AgentGovernor.Phase.ERROR -> Triple(terminalError, Icons.Filled.Warning, "error")
    else -> Triple(terminalOnSurface, Icons.Filled.PlayArrow, execution.phase.name.lowercase())
  }

  val durationLabel = execution.finishedAtMs?.let { end ->
    val elapsed = (end - execution.startedAtMs).coerceAtLeast(0)
    "%.2fs".format(elapsed / 1000.0)
  } ?: "—"

  Column(
    modifier = modifier
      .fillMaxWidth()
      .clip(RoundedCornerShape(8.dp))
      .background(terminalMidGrey)
      .border(
        width = if (isPulsing) 1.5.dp else 1.dp,
        color = if (isPulsing) neonGreen.copy(alpha = borderAlpha) else terminalOutline,
        shape = RoundedCornerShape(8.dp),
      )
      .clickable { expanded = !expanded }
      .padding(horizontal = 12.dp, vertical = 10.dp),
  ) {
    // ── Header row ─────────────────────────────────────────────────────
    Row(
      verticalAlignment = Alignment.CenterVertically,
      modifier = Modifier.fillMaxWidth(),
    ) {
      Icon(
        imageVector = Icons.Filled.ExpandMore,
        contentDescription = if (expanded) "collapse" else "expand",
        tint = MaterialTheme.customColors.linkColor.takeOrElse(neonGreen),
        modifier = Modifier.rotate(rotation).width(18.dp).height(18.dp),
      )
      Spacer(Modifier.width(8.dp))
      Icon(
        imageVector = statusIcon,
        contentDescription = statusLabel,
        tint = statusColor,
        modifier = Modifier.width(16.dp).height(16.dp),
      )
      Spacer(Modifier.width(8.dp))
      Text(
        text = "tool",
        color = terminalOnSurface.copy(alpha = 0.55f),
        fontFamily = FontFamily.Monospace,
        fontSize = 13.sp,
      )
      Spacer(Modifier.width(6.dp))
      Text(
        text = "▸",
        color = terminalOnSurface.copy(alpha = 0.4f),
        fontFamily = FontFamily.Monospace,
        fontSize = 13.sp,
      )
      Spacer(Modifier.width(6.dp))
      Text(
        text = execution.name,
        color = neonGreen,
        fontFamily = FontFamily.Monospace,
        fontSize = 13.sp,
        fontWeight = FontWeight.Medium,
      )
      Spacer(Modifier.width(12.dp))
      Text(
        text = statusLabel,
        color = statusColor,
        fontFamily = FontFamily.Monospace,
        fontSize = 12.sp,
      )
      Spacer(modifier = Modifier.weight(1f))
      Text(
        text = durationLabel,
        color = terminalOnSurface.copy(alpha = 0.55f),
        fontFamily = FontFamily.Monospace,
        fontSize = 12.sp,
      )
    }

    // ── Expanded payload ───────────────────────────────────────────────
    AnimatedVisibility(
      visible = expanded,
      enter = expandVertically(),
      exit = shrinkVertically(),
    ) {
      Column(
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier
          .fillMaxWidth()
          .padding(top = 10.dp),
      ) {
        SectionLabel("args")
        MonoBlock(text = execution.args.ifBlank { "(none)" })

        execution.resultPreview?.let {
          SectionLabel("result")
          MonoBlock(text = it.ifBlank { "(no output)" })
        }
        execution.errorMessage?.let {
          SectionLabel("error")
          MonoBlock(text = it, accent = terminalError)
        }
      }
    }
  }
}

/** Small uppercase neon-green label used inside the expanded payload. */
@Composable
private fun SectionLabel(label: String) {
  Text(
    text = label.uppercase(),
    color = neonGreen.copy(alpha = 0.85f),
    fontFamily = FontFamily.Monospace,
    fontSize = 10.sp,
    fontWeight = FontWeight.SemiBold,
  )
}

/** Monospace block with an inset border, used for args / result / error. */
@Composable
private fun MonoBlock(text: String, accent: Color = terminalOnSurface) {
  val scroll = rememberScrollState()
  Box(
    modifier = Modifier
      .fillMaxWidth()
      .clip(RoundedCornerShape(6.dp))
      .background(terminalLightGrey)
      .border(1.dp, terminalOutline, RoundedCornerShape(6.dp))
      .padding(10.dp),
  ) {
    Text(
      text = text,
      color = accent,
      fontFamily = FontFamily.Monospace,
      fontSize = 12.sp,
      modifier = Modifier.verticalScroll(scroll),
    )
  }
}

/** Local helper because Compose's `Color.takeOrElse` only exists for state. */
private fun Color.takeOrElse(fallback: Color): Color = if (alpha == 0f) fallback else this
