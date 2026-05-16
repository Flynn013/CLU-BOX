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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.DataObject
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.ai.edge.gallery.customtasks.agentchat.SkillManagerViewModel
import com.google.ai.edge.gallery.ui.theme.absoluteBlack
import com.google.ai.edge.gallery.ui.theme.neonGreen
import com.google.ai.edge.gallery.ui.theme.terminalLightGrey
import com.google.ai.edge.gallery.ui.theme.terminalMidGrey
import com.google.ai.edge.gallery.ui.theme.terminalOnSurface
import com.google.ai.edge.gallery.ui.theme.terminalOutline

/**
 * EXT_BOX — Preconfigured toggleable extension registry for CLU.
 *
 * Shows a curated list of capability extensions that map to the built-in
 * skill layer.  Each extension can be toggled on/off via a neon-green switch.
 * The toggle state is persisted through [SkillManagerViewModel] and immediately
 * reflected in the active agent context on the next session reset.
 *
 * Extensions are grouped by category: CORE, INTELLIGENCE, INTEGRATION.
 */
@Composable
fun ExtBoxScreen(skillManagerViewModel: SkillManagerViewModel) {
  val uiState by skillManagerViewModel.uiState.collectAsState()

  // Build a lookup set of currently-enabled skill names for O(1) toggle reads.
  val enabledNames: Set<String> = uiState.skills
    .filter { it.skill.selected }
    .map { it.skill.name }
    .toSet()

  LazyColumn(
    modifier = Modifier
      .fillMaxSize()
      .background(absoluteBlack)
      .padding(horizontal = 12.dp, vertical = 8.dp),
    verticalArrangement = Arrangement.spacedBy(6.dp),
  ) {
    item {
      Text(
        "EXT_BOX",
        color = neonGreen,
        fontFamily = FontFamily.Monospace,
        fontSize = 18.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(bottom = 4.dp),
      )
      Text(
        "Toggle capability extensions for CLU.\nChanges take effect on the next chat session.",
        color = terminalOnSurface.copy(alpha = 0.65f),
        fontFamily = FontFamily.Monospace,
        fontSize = 12.sp,
        lineHeight = 17.sp,
        modifier = Modifier.padding(bottom = 12.dp),
      )
    }

    // ── CORE group ────────────────────────────────────────────────────────
    item { ExtGroupHeader("CORE") }

    items(CORE_EXTENSIONS) { ext ->
      ExtensionCard(
        extension = ext,
        enabled = enabledNames.containsAny(ext.skillNames),
        onToggle = { on ->
          ext.skillNames.forEach { skillName ->
            skillManagerViewModel.setSkillEnabled(skillName, on)
          }
        },
      )
    }

    item { Spacer(Modifier.height(8.dp)); ExtGroupHeader("INTELLIGENCE") }

    items(INTELLIGENCE_EXTENSIONS) { ext ->
      ExtensionCard(
        extension = ext,
        enabled = enabledNames.containsAny(ext.skillNames),
        onToggle = { on ->
          ext.skillNames.forEach { skillName ->
            skillManagerViewModel.setSkillEnabled(skillName, on)
          }
        },
      )
    }

    item { Spacer(Modifier.height(8.dp)); ExtGroupHeader("INTEGRATION") }

    items(INTEGRATION_EXTENSIONS) { ext ->
      ExtensionCard(
        extension = ext,
        enabled = enabledNames.containsAny(ext.skillNames),
        onToggle = { on ->
          ext.skillNames.forEach { skillName ->
            skillManagerViewModel.setSkillEnabled(skillName, on)
          }
        },
      )
    }

    item { Spacer(Modifier.height(16.dp)) }
  }
}

// ── Extension card ────────────────────────────────────────────────────────────

@Composable
private fun ExtensionCard(
  extension: ExtensionDef,
  enabled: Boolean,
  onToggle: (Boolean) -> Unit,
) {
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .background(terminalMidGrey, RoundedCornerShape(8.dp))
      .border(
        width = if (enabled) 1.5.dp else 1.dp,
        color = if (enabled) neonGreen.copy(alpha = 0.6f) else terminalOutline,
        shape = RoundedCornerShape(8.dp),
      )
      .clickable { onToggle(!enabled) }
      .padding(horizontal = 14.dp, vertical = 12.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    // Icon
    Box(
      modifier = Modifier
        .size(40.dp)
        .background(
          color = if (enabled) neonGreen.copy(alpha = 0.1f) else terminalLightGrey,
          shape = RoundedCornerShape(8.dp),
        ),
      contentAlignment = Alignment.Center,
    ) {
      Icon(
        imageVector = extension.icon,
        contentDescription = null,
        tint = if (enabled) neonGreen else terminalOnSurface.copy(alpha = 0.5f),
        modifier = Modifier.size(22.dp),
      )
    }

    Spacer(Modifier.width(12.dp))

    // Labels
    Column(modifier = Modifier.weight(1f)) {
      Text(
        extension.label,
        color = if (enabled) Color.White else Color.White.copy(alpha = 0.7f),
        fontFamily = FontFamily.Monospace,
        fontSize = 14.sp,
        fontWeight = FontWeight.Medium,
      )
      Spacer(Modifier.height(2.dp))
      Text(
        extension.description,
        color = terminalOnSurface.copy(alpha = 0.55f),
        fontFamily = FontFamily.Monospace,
        fontSize = 11.sp,
        lineHeight = 15.sp,
      )
      if (extension.skillNames.size > 1) {
        Spacer(Modifier.height(3.dp))
        Text(
          "skills: ${extension.skillNames.joinToString(", ")}",
          color = neonGreen.copy(alpha = 0.45f),
          fontFamily = FontFamily.Monospace,
          fontSize = 10.sp,
        )
      }
    }

    Spacer(Modifier.width(8.dp))

    // Toggle
    Switch(
      checked = enabled,
      onCheckedChange = onToggle,
      colors = SwitchDefaults.colors(
        checkedThumbColor = absoluteBlack,
        checkedTrackColor = neonGreen,
        uncheckedThumbColor = terminalOnSurface.copy(alpha = 0.6f),
        uncheckedTrackColor = terminalLightGrey,
      ),
    )
  }
}

@Composable
private fun ExtGroupHeader(label: String) {
  Row(
    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    Text(
      label,
      color = neonGreen.copy(alpha = 0.7f),
      fontFamily = FontFamily.Monospace,
      fontSize = 11.sp,
      fontWeight = FontWeight.SemiBold,
      letterSpacing = 2.sp,
    )
    Box(
      modifier = Modifier
        .weight(1f)
        .height(1.dp)
        .background(terminalOutline),
    )
  }
}

// ── Extension definitions ─────────────────────────────────────────────────────

private data class ExtensionDef(
  val label: String,
  val description: String,
  val icon: ImageVector,
  /** One or more skill names this extension maps to. */
  val skillNames: List<String>,
)

private val CORE_EXTENSIONS = listOf(
  ExtensionDef(
    label = "BusyBox Shell",
    description = "Run POSIX shell commands via embedded BusyBox sh. Required for file ops and OS interaction.",
    icon = Icons.Outlined.Terminal,
    skillNames = listOf("shellExecute"),
  ),
  ExtensionDef(
    label = "Python Exec",
    description = "Execute Python 3.11 scripts in-process via Chaquopy. Ideal for math, data, and automation.",
    icon = Icons.Outlined.Code,
    skillNames = listOf("PYTHON_EXEC"),
  ),
  ExtensionDef(
    label = "File Workspace",
    description = "Read and write files in the sandboxed FILE_BOX workspace. CLU uses this to manage projects.",
    icon = Icons.Outlined.FolderOpen,
    skillNames = listOf("fileBoxWrite", "fileBoxReadLines"),
  ),
)

private val INTELLIGENCE_EXTENSIONS = listOf(
  ExtensionDef(
    label = "BrainBox Memory",
    description = "Persistent long-term memory. CLU searches memory before answering to maintain context.",
    icon = Icons.Outlined.Hub,
    skillNames = listOf("memorySearch", "memoryWrite"),
  ),
  ExtensionDef(
    label = "Web Fetch",
    description = "Fetch live web pages and parse content. Enables research, API calls, and live data lookup.",
    icon = Icons.Outlined.Language,
    skillNames = listOf("webFetch"),
  ),
  ExtensionDef(
    label = "Delegate",
    description = "Multi-step task delegation — CLU can hand off sub-tasks to itself with a fresh context.",
    icon = Icons.Outlined.SwapHoriz,
    skillNames = listOf("delegate"),
  ),
)

private val INTEGRATION_EXTENSIONS = listOf(
  ExtensionDef(
    label = "File Grep",
    description = "Search file content with regex patterns across the workspace. Useful for large codebases.",
    icon = Icons.Outlined.DataObject,
    skillNames = listOf("fileGrep"),
  ),
  ExtensionDef(
    label = "Todo Manager",
    description = "Create, list, and complete persistent to-do items. Tracks CLU's own task queue.",
    icon = Icons.Outlined.Psychology,
    skillNames = listOf("todo"),
  ),
  ExtensionDef(
    label = "Scheduled Tasks",
    description = "Schedule commands and Python scripts to run at a future time via SCDL_BOX.",
    icon = Icons.Outlined.Schedule,
    skillNames = listOf("scheduleTask"),
  ),
)

// ── Helpers ───────────────────────────────────────────────────────────────────

private fun Set<String>.containsAny(names: List<String>): Boolean = names.any { contains(it) }
