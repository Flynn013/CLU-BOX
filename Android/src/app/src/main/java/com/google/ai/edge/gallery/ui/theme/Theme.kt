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

package com.google.ai.edge.gallery.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val lightScheme =
  lightColorScheme(
    primary = primaryLight,
    onPrimary = onPrimaryLight,
    primaryContainer = primaryContainerLight,
    onPrimaryContainer = onPrimaryContainerLight,
    secondary = secondaryLight,
    onSecondary = onSecondaryLight,
    secondaryContainer = secondaryContainerLight,
    onSecondaryContainer = onSecondaryContainerLight,
    tertiary = tertiaryLight,
    onTertiary = onTertiaryLight,
    tertiaryContainer = tertiaryContainerLight,
    onTertiaryContainer = onTertiaryContainerLight,
    error = errorLight,
    onError = onErrorLight,
    errorContainer = errorContainerLight,
    onErrorContainer = onErrorContainerLight,
    background = backgroundLight,
    onBackground = onBackgroundLight,
    surface = surfaceLight,
    onSurface = onSurfaceLight,
    surfaceVariant = surfaceVariantLight,
    onSurfaceVariant = onSurfaceVariantLight,
    outline = outlineLight,
    outlineVariant = outlineVariantLight,
    scrim = scrimLight,
    inverseSurface = inverseSurfaceLight,
    inverseOnSurface = inverseOnSurfaceLight,
    inversePrimary = inversePrimaryLight,
    surfaceDim = surfaceDimLight,
    surfaceBright = surfaceBrightLight,
    surfaceContainerLowest = surfaceContainerLowestLight,
    surfaceContainerLow = surfaceContainerLowLight,
    surfaceContainer = surfaceContainerLight,
    surfaceContainerHigh = surfaceContainerHighLight,
    surfaceContainerHighest = surfaceContainerHighestLight,
  )

private val darkScheme =
  darkColorScheme(
    primary = primaryDark,
    onPrimary = onPrimaryDark,
    primaryContainer = primaryContainerDark,
    onPrimaryContainer = onPrimaryContainerDark,
    secondary = secondaryDark,
    onSecondary = onSecondaryDark,
    secondaryContainer = secondaryContainerDark,
    onSecondaryContainer = onSecondaryContainerDark,
    tertiary = tertiaryDark,
    onTertiary = onTertiaryDark,
    tertiaryContainer = tertiaryContainerDark,
    onTertiaryContainer = onTertiaryContainerDark,
    error = errorDark,
    onError = onErrorDark,
    errorContainer = errorContainerDark,
    onErrorContainer = onErrorContainerDark,
    background = backgroundDark,
    onBackground = onBackgroundDark,
    surface = surfaceDark,
    onSurface = onSurfaceDark,
    surfaceVariant = surfaceVariantDark,
    onSurfaceVariant = onSurfaceVariantDark,
    outline = outlineDark,
    outlineVariant = outlineVariantDark,
    scrim = scrimDark,
    inverseSurface = inverseSurfaceDark,
    inverseOnSurface = inverseOnSurfaceDark,
    inversePrimary = inversePrimaryDark,
    surfaceDim = surfaceDimDark,
    surfaceBright = surfaceBrightDark,
    surfaceContainerLowest = surfaceContainerLowestDark,
    surfaceContainerLow = surfaceContainerLowDark,
    surfaceContainer = surfaceContainerDark,
    surfaceContainerHigh = surfaceContainerHighDark,
    surfaceContainerHighest = surfaceContainerHighestDark,
  )

@Immutable
data class CustomColors(
  val appTitleGradientColors: List<Color> = listOf(),
  val tabHeaderBgColor: Color = Color.Transparent,
  val taskCardBgColor: Color = Color.Transparent,
  val taskBgColors: List<Color> = listOf(),
  val taskBgGradientColors: List<List<Color>> = listOf(),
  val taskIconColors: List<Color> = listOf(),
  val taskIconShapeBgColor: Color = Color.Transparent,
  val homeBottomGradient: List<Color> = listOf(),
  val userBubbleBgColor: Color = Color.Transparent,
  val agentBubbleBgColor: Color = Color.Transparent,
  val linkColor: Color = Color.Transparent,
  val successColor: Color = Color.Transparent,
  val recordButtonBgColor: Color = Color.Transparent,
  val waveFormBgColor: Color = Color.Transparent,
  val modelInfoIconColor: Color = Color.Transparent,
  val warningContainerColor: Color = Color.Transparent,
  val warningTextColor: Color = Color.Transparent,
  val errorContainerColor: Color = Color.Transparent,
  val errorTextColor: Color = Color.Transparent,
  val newFeatureContainerColor: Color = Color.Transparent,
  val newFeatureTextColor: Color = Color.Transparent,
  val bgStarColor: Color = Color.Transparent,
  val promoBannerBgBrush: Brush = Brush.verticalGradient(listOf(Color.Transparent)),
  val promoBannerIconBgBrush: Brush = Brush.verticalGradient(listOf(Color.Transparent)),
)

val LocalCustomColors = staticCompositionLocalOf { CustomColors() }

val lightCustomColors =
  CustomColors(
    appTitleGradientColors = listOf(neonGreen, neonGreen),
    tabHeaderBgColor = absoluteBlack,
    taskCardBgColor = terminalMidGrey,
    taskBgColors =
      listOf(terminalDarkGrey, terminalDarkGrey, terminalDarkGrey, terminalDarkGrey),
    taskBgGradientColors =
      listOf(
        listOf(terminalMidGrey, absoluteBlack),
        listOf(terminalMidGrey, absoluteBlack),
        listOf(terminalMidGrey, absoluteBlack),
        listOf(terminalMidGrey, absoluteBlack),
      ),
    taskIconColors =
      listOf(neonGreen, neonGreen, neonGreen, neonGreen),
    taskIconShapeBgColor = absoluteBlack,
    homeBottomGradient = listOf(Color(0x00000000), absoluteBlack),
    agentBubbleBgColor = terminalDarkGrey,
    userBubbleBgColor = terminalMidGrey,
    linkColor = neonGreen,
    successColor = neonGreen,
    recordButtonBgColor = neonGreen,
    waveFormBgColor = terminalOutline,
    modelInfoIconColor = terminalOutline,
    warningContainerColor = terminalMidGrey,
    warningTextColor = neonGreen,
    errorContainerColor = terminalErrorContainer,
    errorTextColor = terminalError,
    newFeatureContainerColor = terminalMidGrey,
    newFeatureTextColor = neonGreen,
    bgStarColor = Color(0x19FFFFFF),
    promoBannerBgBrush =
      Brush.linearGradient(
        colorStops =
          arrayOf(
            0.0f to Color(0xFF0D0D0D),
            1.0f to absoluteBlack,
          ),
        start = Offset(0f, 0f),
        end = Offset(0f, Float.POSITIVE_INFINITY),
      ),
    promoBannerIconBgBrush =
      Brush.linearGradient(
        colorStops =
          arrayOf(
            0.0f to Color(0xFF1A1A1A),
            1.0f to absoluteBlack,
          ),
        start = Offset(0f, 1f),
        end = Offset(1f, 0f),
      ),
  )

val darkCustomColors = lightCustomColors

val MaterialTheme.customColors: CustomColors
  @Composable @ReadOnlyComposable get() = LocalCustomColors.current

/**
 * Controls the color of the phone's status bar icons based on whether the app is using a dark
 * theme.
 */
@Composable
fun StatusBarColorController(useDarkTheme: Boolean) {
  val view = LocalView.current
  val currentWindow = (view.context as? Activity)?.window

  if (currentWindow != null) {
    SideEffect {
      WindowCompat.setDecorFitsSystemWindows(currentWindow, false)
      val controller = WindowCompat.getInsetsController(currentWindow, view)
      controller.isAppearanceLightStatusBars = !useDarkTheme // Set to true for light icons
    }
  }
}

@Composable
fun GalleryTheme(content: @Composable () -> Unit) {
  // CLU/BOX: dark mode is always forced — no light mode.
  val darkTheme = true
  val view = LocalView.current

  StatusBarColorController(useDarkTheme = darkTheme)

  // Read custom color overrides from ThemeSettings.
  val useCustomColors by ThemeSettings.useCustomColors
  val customBg by ThemeSettings.customBackgroundColor
  val customText by ThemeSettings.customTextColor
  val customAccent by ThemeSettings.customAccentColor

  val colorScheme = if (useCustomColors) {
    buildCustomColorScheme(bg = customBg, text = customText, accent = customAccent)
  } else {
    darkScheme
  }

  val customColorsPalette = if (useCustomColors) {
    buildCustomColorsPalette(bg = customBg, text = customText, accent = customAccent)
  } else {
    darkCustomColors
  }

  CompositionLocalProvider(LocalCustomColors provides customColorsPalette) {
    MaterialTheme(colorScheme = colorScheme, typography = AppTypography, content = content)
  }

  // Make sure the navigation bar stays transparent on manual theme changes.
  LaunchedEffect(darkTheme) {
    val window = (view.context as Activity).window

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
      window.isNavigationBarContrastEnforced = false
    }
  }
}

/** Builds a Material3 color scheme from three user-specified colors. */
private fun buildCustomColorScheme(bg: Color, text: Color, accent: Color) = darkColorScheme(
  primary = accent,
  onPrimary = if (accent.luminance() > 0.4f) Color.Black else Color.White,
  primaryContainer = accent.copy(alpha = 0.2f).compositeOver(bg),
  onPrimaryContainer = accent,
  secondary = accent,
  onSecondary = if (accent.luminance() > 0.4f) Color.Black else Color.White,
  secondaryContainer = accent.copy(alpha = 0.15f).compositeOver(bg),
  onSecondaryContainer = accent,
  tertiary = accent,
  onTertiary = if (accent.luminance() > 0.4f) Color.Black else Color.White,
  tertiaryContainer = accent.copy(alpha = 0.15f).compositeOver(bg),
  onTertiaryContainer = accent,
  error = terminalError,
  onError = Color.Black,
  errorContainer = terminalErrorContainer,
  onErrorContainer = terminalError,
  background = bg,
  onBackground = text,
  surface = bg,
  onSurface = text,
  surfaceVariant = blend(bg, Color.White, 0.06f),
  onSurfaceVariant = text.copy(alpha = 0.75f),
  outline = text.copy(alpha = 0.30f),
  outlineVariant = text.copy(alpha = 0.15f),
  scrim = Color.Black,
  inverseSurface = text,
  inverseOnSurface = bg,
  inversePrimary = bg,
  surfaceDim = bg,
  surfaceBright = blend(bg, Color.White, 0.10f),
  surfaceContainerLowest = bg,
  surfaceContainerLow = blend(bg, Color.White, 0.04f),
  surfaceContainer = blend(bg, Color.White, 0.08f),
  surfaceContainerHigh = blend(bg, Color.White, 0.12f),
  surfaceContainerHighest = blend(bg, Color.White, 0.16f),
)

/** Builds a [CustomColors] palette from three user-specified colors. */
private fun buildCustomColorsPalette(bg: Color, text: Color, accent: Color): CustomColors {
  val surface = blend(bg, Color.White, 0.08f)
  val bubbleSurface = blend(bg, Color.White, 0.12f)
  return CustomColors(
    appTitleGradientColors = listOf(accent, accent),
    tabHeaderBgColor = bg,
    taskCardBgColor = surface,
    taskBgColors = listOf(bg, bg, bg, bg),
    taskBgGradientColors = listOf(
      listOf(surface, bg),
      listOf(surface, bg),
      listOf(surface, bg),
      listOf(surface, bg),
    ),
    taskIconColors = listOf(accent, accent, accent, accent),
    taskIconShapeBgColor = bg,
    homeBottomGradient = listOf(Color(0x00000000), bg),
    agentBubbleBgColor = surface,
    userBubbleBgColor = bubbleSurface,
    linkColor = accent,
    successColor = accent,
    recordButtonBgColor = accent,
    waveFormBgColor = text.copy(alpha = 0.25f),
    modelInfoIconColor = text.copy(alpha = 0.40f),
    warningContainerColor = surface,
    warningTextColor = accent,
    errorContainerColor = terminalErrorContainer,
    errorTextColor = terminalError,
    newFeatureContainerColor = surface,
    newFeatureTextColor = accent,
    bgStarColor = accent.copy(alpha = 0.10f),
    promoBannerBgBrush = Brush.linearGradient(
      colorStops = arrayOf(0.0f to blend(bg, Color.White, 0.08f), 1.0f to bg),
      start = Offset(0f, 0f),
      end = Offset(0f, Float.POSITIVE_INFINITY),
    ),
    promoBannerIconBgBrush = Brush.linearGradient(
      colorStops = arrayOf(0.0f to blend(bg, Color.White, 0.12f), 1.0f to bg),
      start = Offset(0f, 1f),
      end = Offset(1f, 0f),
    ),
  )
}

/** Linearly interpolates [from] toward [to] by [fraction] (0 = from, 1 = to). */
private fun blend(from: Color, to: Color, fraction: Float) = Color(
  red = (from.red + (to.red - from.red) * fraction).coerceIn(0f, 1f),
  green = (from.green + (to.green - from.green) * fraction).coerceIn(0f, 1f),
  blue = (from.blue + (to.blue - from.blue) * fraction).coerceIn(0f, 1f),
  alpha = from.alpha,
)

/** Composites [this] (with alpha) on top of [background]. */
private fun Color.compositeOver(background: Color): Color {
  val a = alpha
  return Color(
    red = red * a + background.red * (1f - a),
    green = green * a + background.green * (1f - a),
    blue = blue * a + background.blue * (1f - a),
    alpha = 1f,
  )
}
