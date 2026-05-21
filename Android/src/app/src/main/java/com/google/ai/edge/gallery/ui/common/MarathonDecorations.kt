/*
 * Copyright 2026 Flynn013 / CLU/BOX
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package com.google.ai.edge.gallery.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.ai.edge.gallery.ui.theme.absoluteBlack
import com.google.ai.edge.gallery.ui.theme.neonGreen
import com.google.ai.edge.gallery.ui.theme.terminalOnSurface
import com.google.ai.edge.gallery.ui.theme.terminalOutline

/**
 * Marathon-universe UI decoration components.
 *
 * Visual language taken directly from Marathon (Bungie) reference imagery:
 * acid-lime accent on pure black, crosshair registration marks, faction
 * symbols, coordinate-readout metadata bars, and tight monospace labelling.
 */

// ── CrosshairMark ──────────────────────────────────────────────────────────

/** Small `+` registration crosshair — the canonical Marathon UI punctuation. */
@Composable
fun CrosshairMark(
  modifier: Modifier = Modifier,
  size: Dp = 14.dp,
  color: Color = neonGreen,
  strokeWidth: Dp = 1.dp,
) {
  Box(modifier.size(size), contentAlignment = Alignment.Center) {
    Box(
      Modifier
        .fillMaxWidth()
        .height(strokeWidth)
        .background(color)
    )
    Box(
      Modifier
        .fillMaxHeight()
        .width(strokeWidth)
        .background(color)
    )
  }
}

// ── MarathonCornerMarks ────────────────────────────────────────────────────

/**
 * Wraps [content] in a Box and overlays a [CrosshairMark] at each corner —
 * identical to the registration marks that appear on all Marathon UI panels.
 */
@Composable
fun MarathonCornerMarks(
  modifier: Modifier = Modifier,
  markSize: Dp = 14.dp,
  markPadding: Dp = 4.dp,
  color: Color = neonGreen,
  content: @Composable BoxScope.() -> Unit,
) {
  Box(modifier) {
    content()
    CrosshairMark(
      modifier = Modifier.align(Alignment.TopStart).padding(markPadding),
      size = markSize,
      color = color,
    )
    CrosshairMark(
      modifier = Modifier.align(Alignment.TopEnd).padding(markPadding),
      size = markSize,
      color = color,
    )
    CrosshairMark(
      modifier = Modifier.align(Alignment.BottomStart).padding(markPadding),
      size = markSize,
      color = color,
    )
    CrosshairMark(
      modifier = Modifier.align(Alignment.BottomEnd).padding(markPadding),
      size = markSize,
      color = color,
    )
  }
}

// ── MarathonSectionLabel ───────────────────────────────────────────────────

/**
 * Section label with a thick left-edge accent bar and spaced all-caps mono
 * lettering — matches the faction/category headers in Marathon UI art.
 */
@Composable
fun MarathonSectionLabel(
  title: String,
  modifier: Modifier = Modifier,
  subtitle: String? = null,
  accentColor: Color = neonGreen,
) {
  Column(modifier = modifier) {
    Row(
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      Box(
        Modifier
          .width(3.dp)
          .height(18.dp)
          .background(accentColor)
      )
      Text(
        text = title.uppercase(),
        color = accentColor,
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Bold,
        fontSize = 12.sp,
        letterSpacing = 2.sp,
      )
    }
    if (subtitle != null) {
      Text(
        text = subtitle,
        color = terminalOnSurface.copy(alpha = 0.45f),
        fontFamily = FontFamily.Monospace,
        fontSize = 9.sp,
        letterSpacing = 0.5.sp,
        modifier = Modifier.padding(start = 11.dp, top = 1.dp),
      )
    }
  }
}

// ── MarathonMetaBar ────────────────────────────────────────────────────────

/**
 * Slim bottom metadata bar with left-aligned coordinate-style text and a
 * right-side [CrosshairMark] — mirrors "MARATHON / TAU CETI IV" branding lines.
 */
@Composable
fun MarathonMetaBar(
  text: String,
  modifier: Modifier = Modifier,
  textColor: Color = neonGreen.copy(alpha = 0.5f),
  markColor: Color = neonGreen.copy(alpha = 0.35f),
) {
  Row(
    modifier = modifier
      .fillMaxWidth()
      .background(absoluteBlack)
      .padding(horizontal = 10.dp, vertical = 3.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.SpaceBetween,
  ) {
    Text(
      text = text,
      color = textColor,
      fontFamily = FontFamily.Monospace,
      fontSize = 8.sp,
      letterSpacing = 0.8.sp,
    )
    Row(
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
      CrosshairMark(size = 7.dp, color = markColor)
      CrosshairMark(size = 7.dp, color = markColor)
    }
  }
}

// ── MarathonPanelBorder ────────────────────────────────────────────────────

/**
 * Draws a lime 1dp border with corner marks overlay around [content].
 * Used for info/status panels that should feel like Marathon HUD elements.
 */
@Composable
fun MarathonPanelBorder(
  modifier: Modifier = Modifier,
  borderColor: Color = terminalOutline,
  markColor: Color = neonGreen,
  markSize: Dp = 10.dp,
  content: @Composable BoxScope.() -> Unit,
) {
  MarathonCornerMarks(
    modifier = modifier.border(1.dp, borderColor),
    markSize = markSize,
    markPadding = 3.dp,
    color = markColor,
    content = content,
  )
}

// ── MarathonScreenHeader ───────────────────────────────────────────────────

/**
 * Full-width screen header with Marathon styling:
 * accent-colored title in spaced mono caps, dim subtitle, and flanking
 * crosshair marks.  Separated from content by a 1dp lime hairline.
 */
@Composable
fun MarathonScreenHeader(
  title: String,
  modifier: Modifier = Modifier,
  subtitle: String? = null,
  trailingContent: (@Composable () -> Unit)? = null,
) {
  Column(
    modifier = modifier
      .fillMaxWidth()
      .background(absoluteBlack),
  ) {
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = 16.dp, vertical = 10.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.SpaceBetween,
    ) {
      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.weight(1f),
      ) {
        CrosshairMark(size = 12.dp, color = neonGreen)
        Column {
          Text(
            text = title.uppercase(),
            color = neonGreen,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 14.sp,
            letterSpacing = 3.sp,
          )
          if (subtitle != null) {
            Text(
              text = subtitle,
              color = terminalOnSurface.copy(alpha = 0.45f),
              fontFamily = FontFamily.Monospace,
              fontSize = 9.sp,
              letterSpacing = 0.5.sp,
            )
          }
        }
      }
      if (trailingContent != null) trailingContent()
    }
    // Bottom hairline — the Marathon HUD separator
    Box(
      Modifier
        .fillMaxWidth()
        .height(1.dp)
        .background(neonGreen.copy(alpha = 0.6f))
    )
  }
}
