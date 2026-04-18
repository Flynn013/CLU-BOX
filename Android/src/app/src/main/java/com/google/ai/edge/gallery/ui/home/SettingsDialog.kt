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

package com.google.ai.edge.gallery.ui.home

import com.google.android.gms.oss.licenses.OssLicensesMenuActivity
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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MultiChoiceSegmentedButtonRow
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.google.ai.edge.gallery.BuildConfig
import com.google.ai.edge.gallery.R
import com.google.ai.edge.gallery.proto.Theme
import com.google.ai.edge.gallery.ui.common.ClickableLink
import com.google.ai.edge.gallery.ui.common.tos.AppTosDialog
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsDialog(
  curThemeOverride: Theme,
  modelManagerViewModel: ModelManagerViewModel,
  onDismissed: () -> Unit,
) {
  var selectedTheme by remember { mutableStateOf(curThemeOverride) }
  var hfToken by remember { mutableStateOf(modelManagerViewModel.getTokenStatusAndData().data) }
  val dateFormatter = remember {
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
      .withZone(ZoneId.systemDefault())
      .withLocale(Locale.getDefault())
  }
  var customHfToken by remember { mutableStateOf("") }
  var isFocused by remember { mutableStateOf(false) }
  val focusRequester = remember { FocusRequester() }
  val interactionSource = remember { MutableInteractionSource() }
  var showTos by remember { mutableStateOf(false) }

  // UI color customization state — initialized from ThemeSettings.
  var useCustomColors by remember { mutableStateOf(ThemeSettings.useCustomColors.value) }
  var bgHex by remember { mutableStateOf(colorToHex(ThemeSettings.customBackgroundColor.value)) }
  var textHex by remember { mutableStateOf(colorToHex(ThemeSettings.customTextColor.value)) }
  var accentHex by remember { mutableStateOf(colorToHex(ThemeSettings.customAccentColor.value)) }

  Dialog(onDismissRequest = onDismissed) {
    val focusManager = LocalFocusManager.current
    Card(
      modifier =
        Modifier.fillMaxWidth().clickable(
          interactionSource = interactionSource,
          indication = null, // Disable the ripple effect
        ) {
          focusManager.clearFocus()
        },
      shape = RoundedCornerShape(16.dp),
    ) {
      Column(
        modifier = Modifier.padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
      ) {
        // Dialog title and subtitle.
        Column {
          Text(
            "Settings",
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(bottom = 8.dp),
          )
          // Subtitle.
          Text(
            "App version: ${BuildConfig.VERSION_NAME}",
            style = labelSmallNarrow,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.offset(y = (-6).dp),
          )
        }

        Column(
          modifier = Modifier.verticalScroll(rememberScrollState()).weight(1f, fill = false),
          verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
          val context = LocalContext.current
          // Theme switcher.
          Column(modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}) {
            Text(
              "Theme",
              style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Medium),
            )
            MultiChoiceSegmentedButtonRow {
              THEME_OPTIONS.forEachIndexed { index, theme ->
                SegmentedButton(
                  shape =
                    SegmentedButtonDefaults.itemShape(index = index, count = THEME_OPTIONS.size),
                  onCheckedChange = {
                    selectedTheme = theme

                    // Update theme settings.
                    // This will update app's theme.
                    ThemeSettings.themeOverride.value = theme

                    // Save to data store.
                    modelManagerViewModel.saveThemeOverride(theme)

                    // Update ui mode.
                    //
                    // This is necessary to make other Activities launched from MainActivity to have
                    // the correct theme.
                    val uiModeManager =
                      context.applicationContext.getSystemService(Context.UI_MODE_SERVICE)
                        as UiModeManager
                    if (theme == Theme.THEME_AUTO) {
                      uiModeManager.setApplicationNightMode(UiModeManager.MODE_NIGHT_AUTO)
                    } else if (theme == Theme.THEME_LIGHT) {
                      uiModeManager.setApplicationNightMode(UiModeManager.MODE_NIGHT_NO)
                    } else {
                      uiModeManager.setApplicationNightMode(UiModeManager.MODE_NIGHT_YES)
                    }
                  },
                  checked = theme == selectedTheme,
                  label = { Text(themeLabel(theme)) },
                )
              }
            }
          }

          // UI Colors customization.
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
              ColorPickerRow(
                label = "Background",
                hexValue = bgHex,
                onHexChange = { bgHex = it },
                onCommit = { hex ->
                  val color = parseHexColor(hex) ?: return@ColorPickerRow
                  ThemeSettings.customBackgroundColor.value = color
                  modelManagerViewModel.saveCustomColors(
                    useCustom = true,
                    background = color,
                    text = parseHexColor(textHex) ?: ThemeSettings.customTextColor.value,
                    accent = parseHexColor(accentHex) ?: ThemeSettings.customAccentColor.value,
                  )
                },
              )
              ColorPickerRow(
                label = "Text",
                hexValue = textHex,
                onHexChange = { textHex = it },
                onCommit = { hex ->
                  val color = parseHexColor(hex) ?: return@ColorPickerRow
                  ThemeSettings.customTextColor.value = color
                  modelManagerViewModel.saveCustomColors(
                    useCustom = true,
                    background = parseHexColor(bgHex) ?: ThemeSettings.customBackgroundColor.value,
                    text = color,
                    accent = parseHexColor(accentHex) ?: ThemeSettings.customAccentColor.value,
                  )
                },
              )
              ColorPickerRow(
                label = "Accent",
                hexValue = accentHex,
                onHexChange = { accentHex = it },
                onCommit = { hex ->
                  val color = parseHexColor(hex) ?: return@ColorPickerRow
                  ThemeSettings.customAccentColor.value = color
                  modelManagerViewModel.saveCustomColors(
                    useCustom = true,
                    background = parseHexColor(bgHex) ?: ThemeSettings.customBackgroundColor.value,
                    text = parseHexColor(textHex) ?: ThemeSettings.customTextColor.value,
                    accent = color,
                  )
                },
              )
              // Reset to CLU/BOX defaults.
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
                Text("Reset to defaults")
              }
            }
          }

          // HF Token management.
          Column(
            modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = true) {},
            verticalArrangement = Arrangement.spacedBy(4.dp),
          ) {
            Text(
              "HuggingFace access token",
              style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Medium),
            )
            // Show the start of the token.
            val curHfToken = hfToken
            if (curHfToken != null && curHfToken.accessToken.isNotEmpty()) {
              Text(
                curHfToken.accessToken.substring(0, min(16, curHfToken.accessToken.length)) + "...",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
              Text(
                "Expires at: ${dateFormatter.format(Instant.ofEpochMilli(curHfToken.expiresAtMs))}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            } else {
              Text(
                "Not available",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
              Text(
                "The token will be automatically retrieved when a gated model is downloaded",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
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
                modifier =
                  Modifier.fillMaxWidth()
                    .padding(top = 4.dp)
                    .focusRequester(focusRequester)
                    .onFocusChanged { isFocused = it.isFocused },
                onValueChange = { customHfToken = it },
                textStyle = TextStyle(color = MaterialTheme.colorScheme.onSurface),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.onSurface),
              ) { innerTextField ->
                Box(
                  modifier =
                    Modifier.border(
                        width = if (isFocused) 2.dp else 1.dp,
                        color =
                          if (isFocused) MaterialTheme.colorScheme.primary
                          else MaterialTheme.colorScheme.outline,
                        shape = CircleShape,
                      )
                      .height(40.dp),
                  contentAlignment = Alignment.CenterStart,
                ) {
                  Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(modifier = Modifier.padding(start = 16.dp).weight(1f)) {
                      if (customHfToken.isEmpty()) {
                        Text(
                          "Enter token manually",
                          color = MaterialTheme.colorScheme.onSurfaceVariant,
                          style = MaterialTheme.typography.bodySmall,
                        )
                      }
                      innerTextField()
                    }
                    if (customHfToken.isNotEmpty()) {
                      IconButton(modifier = Modifier.offset(x = 1.dp), onClick = handleSaveToken) {
                        Icon(
                          Icons.Rounded.CheckCircle,
                          contentDescription = stringResource(R.string.cd_done_icon),
                        )
                      }
                    }
                  }
                }
              }
            }
          }

          // Third party licenses.
          Column(modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}) {
            Text(
              "Third-party libraries",
              style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Medium),
            )
            OutlinedButton(
              onClick = {
                // Create an Intent to launch a license viewer that displays a list of
                // third-party library names. Clicking a name will show its license content.
                val intent = Intent(context, OssLicensesMenuActivity::class.java)
                context.startActivity(intent)
              }
            ) {
              Text("View licenses")
            }
          }

          // Tos
          Column(modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}) {
            Text(
              stringResource(R.string.settings_dialog_tos_title),
              style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Medium),
            )
            OutlinedButton(onClick = { showTos = true }) {
              Text(stringResource(R.string.settings_dialog_view_app_terms_of_service))
            }
            ClickableLink(
              url = "https://ai.google.dev/gemma/terms",
              linkText = stringResource(R.string.tos_dialog_title_gemma),
              modifier = Modifier.padding(top = 4.dp),
            )
            ClickableLink(
              url = "https://ai.google.dev/gemma/prohibited_use_policy",
              linkText = stringResource(R.string.settings_dialog_gemma_prohibited_use_policy),
              modifier = Modifier.padding(top = 8.dp),
            )
          }
        }

        // Button row.
        Row(
          modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
          horizontalArrangement = Arrangement.End,
        ) {
          // Close button
          Button(onClick = { onDismissed() }) { Text("Close") }
        }
      }
    }
  }

  if (showTos) {
    AppTosDialog(onTosAccepted = { showTos = false }, viewingMode = true)
  }
}

private fun themeLabel(theme: Theme): String {
  return when (theme) {
    Theme.THEME_AUTO -> "Auto"
    Theme.THEME_LIGHT -> "Light"
    Theme.THEME_DARK -> "Dark"
    else -> "Unknown"
  }
}

/** Row with a colored swatch, a label, and a hex (#RRGGBB) text input. */
@Composable
private fun ColorPickerRow(
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
    // Color swatch preview.
    Box(
      modifier = Modifier
        .size(32.dp)
        .background(color = previewColor ?: Color.Transparent, shape = RoundedCornerShape(4.dp))
        .border(
          width = 1.dp,
          color = MaterialTheme.colorScheme.outline,
          shape = RoundedCornerShape(4.dp),
        ),
    )

    Column(modifier = Modifier.weight(1f)) {
      Text(
        label,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      BasicTextField(
        value = localHex,
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = {
          onHexChange(localHex)
          onCommit(localHex)
        }),
        onValueChange = { raw ->
          // Allow only valid hex characters (#RRGGBB — digits and A-F only).
          val cleaned = raw.trimStart().let { if (!it.startsWith("#")) "#$it" else it }
            .filter { it == '#' || it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }
            .take(7)
            .uppercase()
          localHex = cleaned
          if (cleaned.length == 7) {
            onHexChange(cleaned)
          }
        },
        modifier = Modifier
          .fillMaxWidth()
          .onFocusChanged { state ->
            isFocused = state.isFocused
            if (!state.isFocused && localHex.length == 7) {
              onHexChange(localHex)
              onCommit(localHex)
            }
          },
        textStyle = TextStyle(color = MaterialTheme.colorScheme.onSurface),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
      ) { innerTextField ->
        Box(
          modifier = Modifier
            .border(
              width = if (isFocused) 2.dp else 1.dp,
              color = if (isFocused) MaterialTheme.colorScheme.primary
              else MaterialTheme.colorScheme.outline,
              shape = RoundedCornerShape(6.dp),
            )
            .height(36.dp)
            .padding(horizontal = 10.dp),
          contentAlignment = Alignment.CenterStart,
        ) {
          if (localHex.isEmpty()) {
            Text(
              "#RRGGBB",
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              style = MaterialTheme.typography.bodySmall,
            )
          }
          innerTextField()
        }
      }
    }
  }
}

/** Converts a [Color] to a "#RRGGBB" hex string. */
private fun colorToHex(color: Color): String {
  val r = (color.red * 255).toInt().coerceIn(0, 255)
  val g = (color.green * 255).toInt().coerceIn(0, 255)
  val b = (color.blue * 255).toInt().coerceIn(0, 255)
  return "#%02X%02X%02X".format(r, g, b)
}

/**
 * Parses a "#RRGGBB" hex string into a fully-opaque [Color].
 * Returns null if the string is not a valid 7-character hex color.
 */
private fun parseHexColor(hex: String): Color? {
  val h = hex.trim()
  if (h.length != 7 || h[0] != '#') return null
  return try {
    val rgb = h.substring(1).toLong(16)
    Color(
      red = ((rgb shr 16) and 0xFF).toInt() / 255f,
      green = ((rgb shr 8) and 0xFF).toInt() / 255f,
      blue = (rgb and 0xFF).toInt() / 255f,
      alpha = 1f,
    )
  } catch (_: NumberFormatException) {
    null
  }
}
