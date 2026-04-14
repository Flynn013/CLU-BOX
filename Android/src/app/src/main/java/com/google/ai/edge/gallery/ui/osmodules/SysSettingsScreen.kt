/*
 * Copyright 2025 Google LLC
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

import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.google.ai.edge.gallery.ui.theme.absoluteBlack
import com.google.ai.edge.gallery.ui.theme.neonGreen

/** SYS_SETTINGS module — system prompt, toggles, and hardware readouts. */
@Composable
fun SysSettingsScreen() {
  var systemPrompt by remember {
    mutableStateOf("You are CLU, an advanced local AI operating system. Respond concisely and accurately.")
  }
  var streamingEnabled by remember { mutableStateOf(true) }
  var thinkingModeEnabled by remember { mutableStateOf(false) }
  var persistHistoryEnabled by remember { mutableStateOf(true) }

  Column(
    modifier = Modifier
      .fillMaxSize()
      .padding(horizontal = 16.dp)
      .verticalScroll(rememberScrollState()),
    verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    Spacer(Modifier.height(8.dp))
    Text(
      "SYS_SETTINGS",
      style = MaterialTheme.typography.headlineSmall,
      color = neonGreen,
      fontFamily = FontFamily.Monospace,
    )
    Text(
      "[ CORE CONFIGURATION ]",
      style = MaterialTheme.typography.labelSmall,
      color = neonGreen,
      fontFamily = FontFamily.Monospace,
    )
    Divider(color = neonGreen, thickness = 1.dp)

    Text("SYSTEM DIRECTIVES", color = neonGreen, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.labelMedium)
    NeonTextField(
      value = systemPrompt,
      onValueChange = { systemPrompt = it },
      label = "System Prompt",
      singleLine = false,
    )
    Button(
      onClick = { /* TODO: save system prompt to DataStore */ },
      colors = ButtonDefaults.buttonColors(containerColor = neonGreen, contentColor = absoluteBlack),
      modifier = Modifier.fillMaxWidth(),
    ) {
      Text("WRITE DIRECTIVES", fontFamily = FontFamily.Monospace)
    }

    Divider(color = neonGreen, thickness = 1.dp)
    Text("INFERENCE TOGGLES", color = neonGreen, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.labelMedium)

    ToggleRow("Streaming Output", streamingEnabled) { streamingEnabled = it }
    ToggleRow("Thinking Mode (CoT)", thinkingModeEnabled) { thinkingModeEnabled = it }
    ToggleRow("Persist Chat History", persistHistoryEnabled) { persistHistoryEnabled = it }

    Divider(color = neonGreen, thickness = 1.dp)
    Text("HARDWARE STATUS", color = neonGreen, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.labelMedium)

    HardwareRow("Device", Build.MODEL)
    HardwareRow("Android API", Build.VERSION.SDK_INT.toString())
    HardwareRow("CPU ABI", Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown")
    HardwareRow("GPU Vendor", "N/A — query via OpenGL ES")

    Spacer(Modifier.height(24.dp))
  }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
  Row(
    modifier = Modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.SpaceBetween,
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Text(label, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium)
    Switch(
      checked = checked,
      onCheckedChange = onCheckedChange,
      colors = SwitchDefaults.colors(
        checkedThumbColor = neonGreen,
        checkedTrackColor = neonGreen.copy(alpha = 0.3f),
        uncheckedThumbColor = MaterialTheme.colorScheme.onSurfaceVariant,
      ),
    )
  }
}

@Composable
private fun HardwareRow(label: String, value: String) {
  Row(
    modifier = Modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.SpaceBetween,
  ) {
    Text(label, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Text(value, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, color = neonGreen)
  }
}
