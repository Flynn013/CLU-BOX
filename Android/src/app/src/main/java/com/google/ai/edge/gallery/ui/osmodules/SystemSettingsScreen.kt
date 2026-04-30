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

import android.app.UiModeManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MultiChoiceSegmentedButtonRow
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.android.gms.oss.licenses.OssLicensesMenuActivity
import com.google.ai.edge.gallery.BuildConfig
import com.google.ai.edge.gallery.data.LogBoxManager
import com.google.ai.edge.gallery.proto.Theme
import com.google.ai.edge.gallery.ui.common.ClickableLink
import com.google.ai.edge.gallery.ui.modelmanager.ModelManagerViewModel
import com.google.ai.edge.gallery.ui.theme.ThemeSettings
import com.google.ai.edge.gallery.ui.theme.absoluteBlack
import com.google.ai.edge.gallery.ui.theme.labelSmallNarrow
import com.google.ai.edge.gallery.ui.theme.neonGreen
import com.google.ai.edge.gallery.ui.theme.terminalOnSurface
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.min

private val THEME_OPTIONS = listOf(Theme.THEME_AUTO, Theme.THEME_LIGHT, Theme.THEME_DARK)

private val TABS = listOf("CONFIG", "TERMINAL", "INFERENCE", "SYSTEM")

/** Tag-filter sets for each log tab. */
private val TERMINAL_FILTERS = listOf(
  "NativeShellBridge:V", "TermuxSessionBridge:V", "EnvironmentInstaller:V",
  "MstrCtrlScreen:V", "SharedShellManager:V", "TerminalSessionManager:V",
)
private val INFERENCE_FILTERS = listOf(
  "AGAgentEngine:V", "AgentGovernor:V", "CLU_ENGINE:V", "LlmModelHelper:V",
  "ModelHelperExt:V", "AGMainActivity:V",
)

/**
 * SYS_SETTINGS — Full-screen, multi-tabbed settings and diagnostics hub.
 *
 * Tab 0 (CONFIG)    : Appearance overrides, custom colors, HF token, licenses.
 * Tab 1 (TERMINAL)  : Live logcat stream filtered to shell/PTY tags.
 * Tab 2 (INFERENCE) : Live logcat stream filtered to LLM/inference tags.
 * Tab 3 (SYSTEM)    : Unfiltered logcat — crash stack-traces, OOM events, OS kills.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SystemSettingsScreen(
  modelManagerViewModel: ModelManagerViewModel,
  skillManagerViewModel: com.google.ai.edge.gallery.customtasks.agentchat.SkillManagerViewModel,
) {
  val context = LocalContext.current
  var selectedTab by remember { mutableIntStateOf(0) }

  // One LogBoxManager per log tab, each with a distinct tag filter.
  val terminalLogManager = remember { LogBoxManager(context, TERMINAL_FILTERS) }
  val inferenceLogManager = remember { LogBoxManager(context, INFERENCE_FILTERS) }
  val systemLogManager = remember { LogBoxManager(context) } // all tags

  Scaffold(
    containerColor = absoluteBlack,
    floatingActionButton = {
      // Show a "Copy All Logs" FAB on log tabs.
      if (selectedTab > 0) {
        val activeManager = when (selectedTab) {
          1 -> terminalLogManager
          2 -> inferenceLogManager
          else -> systemLogManager
        }
        ExtendedFloatingActionButton(
          onClick = { activeManager.copyToClipboard() },
          containerColor = MaterialTheme.colorScheme.primary,
          contentColor = Color.Black,
          icon = {
            Icon(Icons.Rounded.ContentCopy, contentDescription = null)
          },
          text = { Text("Copy All Logs", fontFamily = FontFamily.Monospace) },
        )
      }
    },
  ) { innerPadding ->
    Column(
      modifier = Modifier
        .fillMaxSize()
        .padding(innerPadding),
    ) {
      // ── Tab bar ────────────────────────────────────────────────────
      PrimaryTabRow(
        selectedTabIndex = selectedTab,
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        contentColor = neonGreen,
      ) {
        TABS.forEachIndexed { index, title ->
          Tab(
            selected = selectedTab == index,
            onClick = { selectedTab = index },
            text = {
              Text(
                title,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                fontWeight = if (selectedTab == index) FontWeight.Bold else FontWeight.Normal,
              )
            },
          )
        }
      }

      // ── Tab content ────────────────────────────────────────────────
      when (selectedTab) {
        0 -> ConfigTab(
          modelManagerViewModel = modelManagerViewModel,
          skillManagerViewModel = skillManagerViewModel,
        )
        1 -> LogTab(
          logBoxManager = terminalLogManager,
          label = "TERMINAL",
          hint = "stdout/stderr from NativeShellBridge · TermuxSessionBridge · EnvironmentInstaller",
        )
        2 -> LogTab(
          logBoxManager = inferenceLogManager,
          label = "INFERENCE",
          hint = "LiteRT token generation · model load · context events",
        )
        3 -> LogTab(
          logBoxManager = systemLogManager,
          label = "SYSTEM",
          hint = "All logcat · crash stack-traces · OOM · OS kill (-9) events",
        )
      }
    }
  }
}

// ─────────────────────────────────────────────────────────────────────────────
// CONFIG tab — appearance overrides, HF token, licenses
// ─────────────────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConfigTab(
  modelManagerViewModel: ModelManagerViewModel,
  skillManagerViewModel: com.google.ai.edge.gallery.customtasks.agentchat.SkillManagerViewModel,
) {
  val dateFormatter = remember {
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
      .withZone(ZoneId.systemDefault())
      .withLocale(Locale.getDefault())
  }
  var selectedTheme by remember { mutableStateOf(ThemeSettings.themeOverride.value) }
  var hfToken by remember { mutableStateOf(modelManagerViewModel.getTokenStatusAndData().data) }
  var customHfToken by remember { mutableStateOf("") }
  var isFocused by remember { mutableStateOf(false) }
  val focusRequester = remember { FocusRequester() }
  val interactionSource = remember { MutableInteractionSource() }

  var useCustomColors by remember { mutableStateOf(ThemeSettings.useCustomColors.value) }
  var bgHex by remember { mutableStateOf(colorToHex(ThemeSettings.customBackgroundColor.value)) }
  var textHex by remember { mutableStateOf(colorToHex(ThemeSettings.customTextColor.value)) }
  var accentHex by remember { mutableStateOf(colorToHex(ThemeSettings.customAccentColor.value)) }

  // Derive initial shell engine from current skill states.
  val skillUiState by skillManagerViewModel.uiState.collectAsState()
  val isVirtualEnabled = remember(skillUiState) {
    skillUiState.skills.find { it.skill.name == "virtualCommand" }?.skill?.selected ?: false
  }
  // 0 = LOCAL COMMAND, 1 = VIRTUAL COMMAND
  var shellEngineIndex by remember(isVirtualEnabled) { mutableIntStateOf(if (isVirtualEnabled) 1 else 0) }

  val context = LocalContext.current
  val focusManager = LocalFocusManager.current

  Column(
    modifier = Modifier
      .fillMaxSize()
      .clickable(
        interactionSource = interactionSource,
        indication = null,
        onClick = { focusManager.clearFocus() },
      )
      .verticalScroll(rememberScrollState())
      .padding(20.dp),
    verticalArrangement = Arrangement.spacedBy(20.dp),
  ) {
    // Version label.
    Text(
      "App version: ${BuildConfig.VERSION_NAME}",
      style = labelSmallNarrow,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    // ── Theme switcher ─────────────────────────────────────────────
    Column(
      modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = true) {},
    ) {
      Text(
        "Theme",
        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Medium),
      )
      MultiChoiceSegmentedButtonRow {
        THEME_OPTIONS.forEachIndexed { index, theme ->
          SegmentedButton(
            shape = SegmentedButtonDefaults.itemShape(index = index, count = THEME_OPTIONS.size),
            onCheckedChange = {
              selectedTheme = theme
              ThemeSettings.themeOverride.value = theme
              modelManagerViewModel.saveThemeOverride(theme)
              val uiModeManager =
                context.applicationContext.getSystemService(Context.UI_MODE_SERVICE) as UiModeManager
              when (theme) {
                Theme.THEME_AUTO -> uiModeManager.setApplicationNightMode(UiModeManager.MODE_NIGHT_AUTO)
                Theme.THEME_LIGHT -> uiModeManager.setApplicationNightMode(UiModeManager.MODE_NIGHT_NO)
                else -> uiModeManager.setApplicationNightMode(UiModeManager.MODE_NIGHT_YES)
              }
            },
            checked = theme == selectedTheme,
            label = { Text(themeLabel(theme)) },
          )
        }
      }
    }

    // ── Shell Engine Toggle ─────────────────────────────────────────
    // Switches between LOCAL COMMAND (native PRoot sandbox) and VIRTUAL COMMAND
    // (external Termux IPC). Activating one automatically disables the other in
    // the SkillRegistry so the LLM only sees the active engine's skill.
    Column(
      modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = true) {},
      verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
      Text(
        "Shell Engine",
        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Medium),
      )
      Text(
        "LOCAL — native PRoot sandbox  ·  VIRTUAL — external Termux IPC",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      MultiChoiceSegmentedButtonRow {
        listOf("LOCAL", "VIRTUAL").forEachIndexed { index, label ->
          SegmentedButton(
            shape = SegmentedButtonDefaults.itemShape(index = index, count = 2),
            onCheckedChange = {
              shellEngineIndex = index
              // Activate local → enable shellExecute, disable virtualCommand.
              // Activate virtual → enable virtualCommand, disable shellExecute.
              val enableLocal = index == 0
              skillManagerViewModel.setSkillEnabled(
                skillName = "shellExecute",
                enabled = enableLocal,
              )
              skillManagerViewModel.setSkillEnabled(
                skillName = "virtualCommand",
                enabled = !enableLocal,
              )
            },
            checked = shellEngineIndex == index,
            label = { Text(label, fontFamily = FontFamily.Monospace) },
          )
        }
      }
    }

    // ── Custom UI colors ───────────────────────────────────────────
    Column(
      modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = true) {},
      verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Text(
          "Custom UI colors",
          style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Medium),
        )
        Switch(
          checked = useCustomColors,
          onCheckedChange = { enabled ->
            useCustomColors = enabled
            ThemeSettings.useCustomColors.value = enabled
            val bg = parseHexColor(bgHex) ?: ThemeSettings.customBackgroundColor.value
            val txt = parseHexColor(textHex) ?: ThemeSettings.customTextColor.value
            val acc = parseHexColor(accentHex) ?: ThemeSettings.customAccentColor.value
            modelManagerViewModel.saveCustomColors(
              useCustom = enabled,
              background = bg,
              text = txt,
              accent = acc,
            )
          },
        )
      }
      if (useCustomColors) {
        SysColorPickerRow(
          label = "Background",
          hexValue = bgHex,
          onHexChange = { bgHex = it },
          onCommit = { hex ->
            val color = parseHexColor(hex) ?: return@SysColorPickerRow
            ThemeSettings.customBackgroundColor.value = color
            modelManagerViewModel.saveCustomColors(
              useCustom = true,
              background = color,
              text = parseHexColor(textHex) ?: ThemeSettings.customTextColor.value,
              accent = parseHexColor(accentHex) ?: ThemeSettings.customAccentColor.value,
            )
          },
        )
        SysColorPickerRow(
          label = "Text",
          hexValue = textHex,
          onHexChange = { textHex = it },
          onCommit = { hex ->
            val color = parseHexColor(hex) ?: return@SysColorPickerRow
            ThemeSettings.customTextColor.value = color
            modelManagerViewModel.saveCustomColors(
              useCustom = true,
              background = parseHexColor(bgHex) ?: ThemeSettings.customBackgroundColor.value,
              text = color,
              accent = parseHexColor(accentHex) ?: ThemeSettings.customAccentColor.value,
            )
          },
        )
        SysColorPickerRow(
          label = "Accent",
          hexValue = accentHex,
          onHexChange = { accentHex = it },
          onCommit = { hex ->
            val color = parseHexColor(hex) ?: return@SysColorPickerRow
            ThemeSettings.customAccentColor.value = color
            modelManagerViewModel.saveCustomColors(
              useCustom = true,
              background = parseHexColor(bgHex) ?: ThemeSettings.customBackgroundColor.value,
              text = parseHexColor(textHex) ?: ThemeSettings.customTextColor.value,
              accent = color,
            )
          },
        )
        OutlinedButton(
          onClick = {
            bgHex = colorToHex(absoluteBlack)
            textHex = colorToHex(terminalOnSurface)
            accentHex = colorToHex(neonGreen)
            ThemeSettings.customBackgroundColor.value = absoluteBlack
            ThemeSettings.customTextColor.value = terminalOnSurface
            ThemeSettings.customAccentColor.value = neonGreen
            modelManagerViewModel.saveCustomColors(
              useCustom = true,
              background = absoluteBlack,
              text = terminalOnSurface,
              accent = neonGreen,
            )
          },
          modifier = Modifier.fillMaxWidth(),
        ) {
          Text("Reset to CLU/BOX defaults")
        }
      }
    }

    // ── HuggingFace token ──────────────────────────────────────────
    Column(
      modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = true) {},
      verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
      Text(
        "HuggingFace access token",
        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Medium),
      )
      val curHfToken = hfToken
      if (curHfToken != null && curHfToken.accessToken.isNotEmpty()) {
        Text(
          curHfToken.accessToken.substring(0, min(16, curHfToken.accessToken.length)) + "...",
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
          "Expires: ${dateFormatter.format(Instant.ofEpochMilli(curHfToken.expiresAtMs))}",
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      } else {
        Text(
          "Not set — will be auto-retrieved when a gated model is downloaded.",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(
          onClick = {
            modelManagerViewModel.clearAccessToken()
            hfToken = null
          },
          enabled = curHfToken != null,
        ) {
          Text("Clear")
        }
        val handleSaveToken = {
          modelManagerViewModel.saveAccessToken(
            accessToken = customHfToken,
            refreshToken = "",
            expiresAt = System.currentTimeMillis() + 1000L * 60 * 60 * 24 * 365 * 10,
          )
          hfToken = modelManagerViewModel.getTokenStatusAndData().data
          focusManager.clearFocus()
        }
        BasicTextField(
          value = customHfToken,
          singleLine = true,
          keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
          keyboardActions = KeyboardActions(onDone = { handleSaveToken() }),
          modifier = Modifier
            .weight(1f)
            .padding(top = 4.dp)
            .focusRequester(focusRequester)
            .onFocusChanged { isFocused = it.isFocused },
          onValueChange = { customHfToken = it },
          textStyle = TextStyle(color = MaterialTheme.colorScheme.onSurface),
          cursorBrush = SolidColor(neonGreen),
        ) { innerTextField ->
          Box(
            modifier = Modifier
              .border(
                width = if (isFocused) 2.dp else 1.dp,
                color = if (isFocused) neonGreen else MaterialTheme.colorScheme.outline,
                shape = CircleShape,
              )
              .height(40.dp),
            contentAlignment = Alignment.CenterStart,
          ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
              Box(modifier = Modifier.padding(start = 16.dp).weight(1f)) {
                if (customHfToken.isEmpty()) {
                  Text(
                    "Paste HF token here",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                  )
                }
                innerTextField()
              }
              if (customHfToken.isNotEmpty()) {
                IconButton(modifier = Modifier.offset(x = 1.dp), onClick = handleSaveToken) {
                  Icon(Icons.Rounded.CheckCircle, contentDescription = "Save token", tint = neonGreen)
                }
              }
            }
          }
        }
      }
    }

    // ── Third-party licenses ───────────────────────────────────────
    Column(modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}) {
      Text(
        "Third-party libraries",
        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Medium),
      )
      OutlinedButton(
        onClick = {
          context.startActivity(Intent(context, OssLicensesMenuActivity::class.java))
        }
      ) {
        Text("View licenses")
      }
    }

    // ── Gemma ToS links ────────────────────────────────────────────
    Column(modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}) {
      Text(
        "Model Terms of Service",
        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Medium),
      )
      ClickableLink(
        url = "https://ai.google.dev/gemma/terms",
        linkText = "Gemma Terms of Use",
        modifier = Modifier.padding(top = 4.dp),
      )
      ClickableLink(
        url = "https://ai.google.dev/gemma/prohibited_use_policy",
        linkText = "Gemma Prohibited Use Policy",
        modifier = Modifier.padding(top = 8.dp),
      )
    }
  }
}

// ─────────────────────────────────────────────────────────────────────────────
// Log tab — reusable live logcat viewer
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun LogTab(
  logBoxManager: LogBoxManager,
  label: String,
  hint: String,
) {
  val logLines by logBoxManager.logLines.collectAsState()
  val listState = rememberLazyListState()

  DisposableEffect(logBoxManager) {
    logBoxManager.startStream()
    onDispose { logBoxManager.stopStream() }
  }

  LaunchedEffect(logLines.size) {
    if (logLines.isNotEmpty()) listState.animateScrollToItem(logLines.size - 1)
  }

  Box(modifier = Modifier.fillMaxSize().background(absoluteBlack)) {
    Column(modifier = Modifier.fillMaxSize()) {
      // Hint bar.
      Text(
        "▸ $hint",
        color = neonGreen.copy(alpha = 0.5f),
        fontFamily = FontFamily.Monospace,
        fontSize = 9.sp,
        modifier = Modifier
          .fillMaxWidth()
          .background(MaterialTheme.colorScheme.surfaceContainer)
          .padding(horizontal = 8.dp, vertical = 4.dp),
      )

      // Log lines.
      LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 4.dp),
      ) {
        itemsIndexed(logLines) { _, line ->
          Text(
            text = line,
            color = lineColor(label, line),
            fontFamily = FontFamily.Monospace,
            fontSize = 10.sp,
            lineHeight = 13.sp,
            modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp),
          )
        }
      }
    }
  }
}

/** Returns a neon-green shade based on logcat severity for visual triage. */
private fun lineColor(tabLabel: String, line: String): Color {
  return when {
    line.contains(" E ") || line.contains(" E/") -> Color(0xFFFF4444)
    line.contains(" W ") || line.contains(" W/") -> Color(0xFFFFBB33)
    line.contains(" D ") || line.contains(" D/") -> neonGreen
    else -> neonGreen.copy(alpha = 0.65f)
  }
}

// ─────────────────────────────────────────────────────────────────────────────
// Color-picker helper (local copy to avoid cross-module coupling)
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun SysColorPickerRow(
  label: String,
  hexValue: String,
  onHexChange: (String) -> Unit,
  onCommit: (String) -> Unit,
) {
  var localHex by remember(hexValue) { mutableStateOf(hexValue) }
  var isFocused by remember { mutableStateOf(false) }
  val previewColor = parseHexColor(localHex)

  Row(
    modifier = Modifier.fillMaxWidth(),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    Box(
      modifier = Modifier
        .size(32.dp)
        .background(previewColor ?: Color.Transparent, RoundedCornerShape(4.dp))
        .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(4.dp)),
    )
    Column(modifier = Modifier.weight(1f)) {
      Text(label, style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
      BasicTextField(
        value = localHex,
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { onHexChange(localHex); onCommit(localHex) }),
        onValueChange = { raw ->
          val cleaned = raw.trimStart().let { if (!it.startsWith("#")) "#$it" else it }
            .filter { it == '#' || it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }
            .take(7).uppercase()
          localHex = cleaned
          if (cleaned.length == 7) onHexChange(cleaned)
        },
        modifier = Modifier.fillMaxWidth().onFocusChanged { state ->
          isFocused = state.isFocused
          if (!state.isFocused && localHex.length == 7) { onHexChange(localHex); onCommit(localHex) }
        },
        textStyle = TextStyle(color = MaterialTheme.colorScheme.onSurface),
        cursorBrush = SolidColor(neonGreen),
      ) { innerTextField ->
        Box(
          modifier = Modifier
            .border(
              width = if (isFocused) 2.dp else 1.dp,
              color = if (isFocused) neonGreen else MaterialTheme.colorScheme.outline,
              shape = RoundedCornerShape(6.dp),
            )
            .height(36.dp)
            .padding(horizontal = 10.dp),
          contentAlignment = Alignment.CenterStart,
        ) {
          if (localHex.isEmpty()) {
            Text("#RRGGBB", color = MaterialTheme.colorScheme.onSurfaceVariant,
              style = MaterialTheme.typography.bodySmall)
          }
          innerTextField()
        }
      }
    }
  }
}

private fun themeLabel(theme: Theme): String = when (theme) {
  Theme.THEME_AUTO -> "Auto"
  Theme.THEME_LIGHT -> "Light"
  Theme.THEME_DARK -> "Dark"
  else -> "Unknown"
}

private fun colorToHex(color: androidx.compose.ui.graphics.Color): String {
  val r = (color.red * 255).toInt().coerceIn(0, 255)
  val g = (color.green * 255).toInt().coerceIn(0, 255)
  val b = (color.blue * 255).toInt().coerceIn(0, 255)
  return "#%02X%02X%02X".format(r, g, b)
}

private fun parseHexColor(hex: String): androidx.compose.ui.graphics.Color? {
  val h = hex.trim()
  if (h.length != 7 || h[0] != '#') return null
  return try {
    val rgb = h.substring(1).toLong(16)
    androidx.compose.ui.graphics.Color(
      red = ((rgb shr 16) and 0xFF).toInt() / 255f,
      green = ((rgb shr 8) and 0xFF).toInt() / 255f,
      blue = (rgb and 0xFF).toInt() / 255f,
      alpha = 1f,
    )
  } catch (_: NumberFormatException) {
    null
  }
}
