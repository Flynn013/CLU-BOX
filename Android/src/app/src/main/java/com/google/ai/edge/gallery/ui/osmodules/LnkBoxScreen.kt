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
import androidx.compose.foundation.border
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.ai.edge.gallery.data.mcp.McpConnectionManager
import com.google.ai.edge.gallery.data.mcp.McpConnectionState
import com.google.ai.edge.gallery.data.mcp.McpConnectionStatus
import com.google.ai.edge.gallery.data.mcp.McpServerConfig
import com.google.ai.edge.gallery.ui.theme.absoluteBlack
import com.google.ai.edge.gallery.ui.theme.neonGreen
import com.google.ai.edge.gallery.ui.theme.terminalLightGrey
import com.google.ai.edge.gallery.ui.theme.terminalMidGrey
import kotlinx.coroutines.launch

// ─────────────────────────────────────────────────────────────────────────────
// Public entry-point
// ─────────────────────────────────────────────────────────────────────────────

/**
 * **LNK_BOX** — the MCP server connection dashboard.
 *
 * Displays all known MCP servers as status cards with neon-green/red indicators and exposes an
 * "ADD LINK" FAB that opens a bottom sheet for adding new servers.  Environment variables entered
 * in the form are forwarded to [McpConnectionManager.addAndConnect] which stores them in the
 * encrypted vault ([com.google.ai.edge.gallery.data.mcp.McpVault]) before injecting them into
 * the subprocess environment.
 *
 * @param mcpConnectionManager Live connection manager provided by the parent composable.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LnkBoxScreen(mcpConnectionManager: McpConnectionManager) {
  val statuses by mcpConnectionManager.statuses.collectAsState()
  var showAddSheet by remember { mutableStateOf(false) }
  val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
  val scope = rememberCoroutineScope()

  Scaffold(
    containerColor = absoluteBlack,
    floatingActionButton = {
      FloatingActionButton(
        onClick = { showAddSheet = true },
        containerColor = neonGreen,
        contentColor = absoluteBlack,
        shape = RoundedCornerShape(12.dp),
      ) {
        Row(
          verticalAlignment = Alignment.CenterVertically,
          modifier = Modifier.padding(horizontal = 16.dp),
        ) {
          Icon(Icons.Default.Add, contentDescription = "Add MCP server", modifier = Modifier.size(18.dp))
          Spacer(Modifier.width(6.dp))
          Text(
            "ADD LINK",
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 13.sp,
          )
        }
      }
    },
  ) { innerPadding ->
    if (statuses.isEmpty()) {
      EmptyLinkState(modifier = Modifier.padding(innerPadding))
    } else {
      LazyColumn(
        modifier = Modifier.fillMaxSize().padding(innerPadding).padding(horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 12.dp),
      ) {
        items(statuses, key = { it.config.name }) { status ->
          ServerStatusCard(
            status = status,
            onDelete = { mcpConnectionManager.deleteServer(status.config.name) },
            onReconnect = { mcpConnectionManager.reconnect(status.config.name) },
            onDisconnect = { mcpConnectionManager.disconnect(status.config.name) },
          )
        }
        item { Spacer(Modifier.height(80.dp)) } // FAB clearance
      }
    }
  }

  if (showAddSheet) {
    ModalBottomSheet(
      onDismissRequest = { showAddSheet = false },
      sheetState = sheetState,
      containerColor = terminalMidGrey,
    ) {
      AddLinkForm(
        onDismiss = {
          scope.launch { sheetState.hide() }.invokeOnCompletion { showAddSheet = false }
        },
        onSubmit = { config ->
          mcpConnectionManager.addAndConnect(config)
          scope.launch { sheetState.hide() }.invokeOnCompletion { showAddSheet = false }
        },
      )
    }
  }
}

// ─────────────────────────────────────────────────────────────────────────────
// Server status card
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun ServerStatusCard(
  status: McpConnectionStatus,
  onDelete: () -> Unit,
  onReconnect: () -> Unit,
  onDisconnect: () -> Unit,
) {
  val dotColor =
    when (status.state) {
      McpConnectionState.CONNECTED -> neonGreen
      McpConnectionState.CONNECTING -> Color(0xFFFFBB00) // amber
      McpConnectionState.ERROR -> Color(0xFFFF3B30)      // red
      McpConnectionState.IDLE -> Color(0xFF555555)       // grey
    }
  val stateLabel = status.state.name

  Box(
    modifier =
      Modifier.fillMaxWidth()
        .clip(RoundedCornerShape(10.dp))
        .background(terminalMidGrey)
        .border(1.dp, terminalLightGrey, RoundedCornerShape(10.dp))
        .padding(14.dp),
  ) {
    Column {
      // ── Header row ───────────────────────────────────────────────────
      Row(verticalAlignment = Alignment.CenterVertically) {
        // Status dot
        Box(
          modifier = Modifier.size(10.dp).clip(CircleShape).background(dotColor)
        )
        Spacer(Modifier.width(10.dp))
        Text(
          status.config.name,
          color = Color.White,
          fontFamily = FontFamily.Monospace,
          fontWeight = FontWeight.Bold,
          fontSize = 14.sp,
          modifier = Modifier.weight(1f),
        )
        // Action buttons
        if (status.state == McpConnectionState.IDLE || status.state == McpConnectionState.ERROR) {
          IconButton(onClick = onReconnect, modifier = Modifier.size(32.dp)) {
            Icon(Icons.Default.Refresh, contentDescription = "Reconnect", tint = neonGreen, modifier = Modifier.size(16.dp))
          }
        } else if (status.state == McpConnectionState.CONNECTED) {
          IconButton(onClick = onDisconnect, modifier = Modifier.size(32.dp)) {
            Icon(Icons.Default.Close, contentDescription = "Disconnect", tint = Color.White.copy(alpha = 0.6f), modifier = Modifier.size(16.dp))
          }
        }
        IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
          Icon(Icons.Default.Delete, contentDescription = "Delete server", tint = Color(0xFFFF3B30), modifier = Modifier.size(16.dp))
        }
      }

      Spacer(Modifier.height(6.dp))

      // ── Command ───────────────────────────────────────────────────────
      Text(
        status.config.command,
        color = Color.White.copy(alpha = 0.55f),
        fontFamily = FontFamily.Monospace,
        fontSize = 11.sp,
        maxLines = 1,
        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
      )

      // ── State / error ──────────────────────────────────────────────────
      Spacer(Modifier.height(6.dp))
      Text(
        if (status.state == McpConnectionState.ERROR) "ERROR: ${status.errorMessage}" else stateLabel,
        color = dotColor.copy(alpha = 0.85f),
        fontFamily = FontFamily.Monospace,
        fontSize = 11.sp,
      )

      // ── Tool list ─────────────────────────────────────────────────────
      if (status.tools.isNotEmpty()) {
        Spacer(Modifier.height(8.dp))
        Text(
          "${status.tools.size} tool(s): ${status.tools.joinToString { it.name }}",
          color = neonGreen.copy(alpha = 0.7f),
          fontFamily = FontFamily.Monospace,
          fontSize = 11.sp,
        )
      }
    }
  }
}

// ─────────────────────────────────────────────────────────────────────────────
// "Add Link" bottom sheet form
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun AddLinkForm(
  onDismiss: () -> Unit,
  onSubmit: (McpServerConfig) -> Unit,
) {
  var serverName by remember { mutableStateOf("") }
  var command by remember { mutableStateOf("") }
  // Each env-var entry is a Pair<key, value> backed by a snapshot state list.
  val envVars = remember { mutableStateListOf<Pair<String, String>>() }
  var nameError by remember { mutableStateOf(false) }
  var commandError by remember { mutableStateOf(false) }

  val neonFieldColors =
    OutlinedTextFieldDefaults.colors(
      focusedBorderColor = neonGreen,
      unfocusedBorderColor = terminalLightGrey,
      focusedLabelColor = neonGreen,
      unfocusedLabelColor = Color.White.copy(alpha = 0.5f),
      focusedTextColor = Color.White,
      unfocusedTextColor = Color.White,
      cursorColor = neonGreen,
      errorBorderColor = Color(0xFFFF3B30),
      errorLabelColor = Color(0xFFFF3B30),
    )

  Column(
    modifier =
      Modifier
        .fillMaxWidth()
        .verticalScroll(rememberScrollState())
        .padding(horizontal = 20.dp)
        .padding(bottom = 32.dp),
    verticalArrangement = Arrangement.spacedBy(14.dp),
  ) {
    // ── Sheet header ────────────────────────────────────────────────────
    Row(verticalAlignment = Alignment.CenterVertically) {
      Icon(Icons.Default.Link, contentDescription = null, tint = neonGreen, modifier = Modifier.size(20.dp))
      Spacer(Modifier.width(8.dp))
      Text(
        "NEW MCP LINK",
        color = neonGreen,
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Bold,
        fontSize = 15.sp,
      )
    }

    Spacer(Modifier.height(4.dp))

    // ── Server Name ─────────────────────────────────────────────────────
    OutlinedTextField(
      value = serverName,
      onValueChange = {
        serverName = it
        nameError = false
      },
      label = { Text("Server Name", fontFamily = FontFamily.Monospace) },
      placeholder = { Text("GitHub MCP", color = Color.White.copy(alpha = 0.3f), fontFamily = FontFamily.Monospace) },
      singleLine = true,
      isError = nameError,
      supportingText = if (nameError) {
        { Text("Server name is required", color = Color(0xFFFF3B30), fontFamily = FontFamily.Monospace, fontSize = 11.sp) }
      } else null,
      colors = neonFieldColors,
      modifier = Modifier.fillMaxWidth(),
    )

    // ── Execution Command ───────────────────────────────────────────────
    OutlinedTextField(
      value = command,
      onValueChange = {
        command = it
        commandError = false
      },
      label = { Text("Execution Command", fontFamily = FontFamily.Monospace) },
      placeholder = {
        Text(
          "npx -y @modelcontextprotocol/server-github",
          color = Color.White.copy(alpha = 0.3f),
          fontFamily = FontFamily.Monospace,
          fontSize = 12.sp,
        )
      },
      singleLine = true,
      isError = commandError,
      supportingText = if (commandError) {
        { Text("Command is required", color = Color(0xFFFF3B30), fontFamily = FontFamily.Monospace, fontSize = 11.sp) }
      } else null,
      colors = neonFieldColors,
      modifier = Modifier.fillMaxWidth(),
    )

    // ── Environment Variables ───────────────────────────────────────────
    Text(
      "ENVIRONMENT VARIABLES",
      color = Color.White.copy(alpha = 0.5f),
      fontFamily = FontFamily.Monospace,
      fontSize = 11.sp,
      fontWeight = FontWeight.Bold,
    )

    envVars.forEachIndexed { index, (key, value) ->
      EnvVarRow(
        envKey = key,
        envValue = value,
        onKeyChange = { envVars[index] = Pair(it, value) },
        onValueChange = { envVars[index] = Pair(key, it) },
        onRemove = { envVars.removeAt(index) },
        fieldColors = neonFieldColors,
      )
    }

    TextButton(
      onClick = { envVars.add(Pair("", "")) },
      colors = ButtonDefaults.textButtonColors(contentColor = neonGreen),
    ) {
      Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
      Spacer(Modifier.width(4.dp))
      Text("ADD VARIABLE", fontFamily = FontFamily.Monospace, fontSize = 12.sp)
    }

    Spacer(Modifier.height(8.dp))

    // ── Action buttons ──────────────────────────────────────────────────
    Row(
      horizontalArrangement = Arrangement.spacedBy(10.dp),
      modifier = Modifier.fillMaxWidth(),
    ) {
      TextButton(
        onClick = onDismiss,
        colors = ButtonDefaults.textButtonColors(contentColor = Color.White.copy(alpha = 0.5f)),
        modifier = Modifier.weight(1f),
      ) {
        Text("CANCEL", fontFamily = FontFamily.Monospace, fontSize = 13.sp)
      }

      Button(
        onClick = {
          nameError = serverName.isBlank()
          commandError = command.isBlank()
          if (nameError || commandError) return@Button

          val envMap =
            envVars
              .filter { (k, _) -> k.isNotBlank() }
              .associate { (k, v) -> k.trim() to v }

          onSubmit(McpServerConfig(name = serverName.trim(), command = command.trim(), envVars = envMap))
        },
        colors = ButtonDefaults.buttonColors(containerColor = neonGreen, contentColor = absoluteBlack),
        modifier = Modifier.weight(1f),
      ) {
        Text("CONNECT", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 13.sp)
      }
    }
  }
}

// ─────────────────────────────────────────────────────────────────────────────
// Env-var row
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun EnvVarRow(
  envKey: String,
  envValue: String,
  onKeyChange: (String) -> Unit,
  onValueChange: (String) -> Unit,
  onRemove: () -> Unit,
  fieldColors: androidx.compose.material3.TextFieldColors,
) {
  Row(verticalAlignment = Alignment.CenterVertically) {
    OutlinedTextField(
      value = envKey,
      onValueChange = onKeyChange,
      label = { Text("KEY", fontFamily = FontFamily.Monospace, fontSize = 11.sp) },
      singleLine = true,
      colors = fieldColors,
      modifier = Modifier.weight(1f),
    )
    Spacer(Modifier.width(6.dp))
    OutlinedTextField(
      value = envValue,
      onValueChange = onValueChange,
      label = { Text("VALUE", fontFamily = FontFamily.Monospace, fontSize = 11.sp) },
      singleLine = true,
      colors = fieldColors,
      modifier = Modifier.weight(1.5f),
    )
    IconButton(onClick = onRemove, modifier = Modifier.size(40.dp)) {
      Icon(
        Icons.Default.Close,
        contentDescription = "Remove variable",
        tint = Color(0xFFFF3B30),
        modifier = Modifier.size(16.dp),
      )
    }
  }
}

// ─────────────────────────────────────────────────────────────────────────────
// Empty state
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun EmptyLinkState(modifier: Modifier = Modifier) {
  Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
      Icon(
        Icons.Default.Link,
        contentDescription = null,
        tint = neonGreen.copy(alpha = 0.3f),
        modifier = Modifier.size(48.dp),
      )
      Text(
        "NO MCP LINKS",
        color = Color.White.copy(alpha = 0.4f),
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Bold,
        fontSize = 14.sp,
      )
      Text(
        "Tap ADD LINK to connect an MCP server",
        color = Color.White.copy(alpha = 0.25f),
        fontFamily = FontFamily.Monospace,
        fontSize = 12.sp,
      )
    }
  }
}
