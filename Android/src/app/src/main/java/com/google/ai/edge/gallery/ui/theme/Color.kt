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

import androidx.compose.ui.graphics.Color

// CLU/BOX Cyberpunk Terminal Palette
// Primary accent: neon green (#4ade80)
// All backgrounds and surfaces: absolute black (#000000)

val neonGreen = Color(0xFF4ade80)
val absoluteBlack = Color(0xFF000000)
val terminalDarkGrey = Color(0xFF0D0D0D)
val terminalMidGrey = Color(0xFF1A1A1A)
val terminalLightGrey = Color(0xFF2A2A2A)
val terminalOutline = Color(0xFF3A3A3A)
val terminalOnSurface = Color(0xFFE0E0E0)
val terminalError = Color(0xFFFF5555)
val terminalErrorContainer = Color(0xFF3D0000)

// Dark-only scheme — all light-mode values mirror dark for forced dark mode.

val primaryLight = neonGreen
val onPrimaryLight = absoluteBlack
val primaryContainerLight = terminalMidGrey
val onPrimaryContainerLight = neonGreen
val secondaryLight = neonGreen
val onSecondaryLight = absoluteBlack
val secondaryContainerLight = terminalMidGrey
val onSecondaryContainerLight = neonGreen
val tertiaryLight = neonGreen
val onTertiaryLight = absoluteBlack
val tertiaryContainerLight = terminalMidGrey
val onTertiaryContainerLight = neonGreen
val errorLight = terminalError
val onErrorLight = absoluteBlack
val errorContainerLight = terminalErrorContainer
val onErrorContainerLight = terminalError
val backgroundLight = absoluteBlack
val onBackgroundLight = terminalOnSurface
val surfaceLight = absoluteBlack
val onSurfaceLight = terminalOnSurface
val surfaceVariantLight = terminalDarkGrey
val onSurfaceVariantLight = terminalOnSurface
val surfaceContainerLowestLight = absoluteBlack
val surfaceContainerLowLight = terminalDarkGrey
val surfaceContainerLight = terminalMidGrey
val surfaceContainerHighLight = terminalLightGrey
val surfaceContainerHighestLight = terminalLightGrey
val inverseSurfaceLight = terminalOnSurface
val inverseOnSurfaceLight = absoluteBlack
val outlineLight = terminalOutline
val outlineVariantLight = terminalMidGrey
val inversePrimaryLight = absoluteBlack
val surfaceDimLight = absoluteBlack
val surfaceBrightLight = terminalMidGrey
val scrimLight = absoluteBlack

val primaryDark = neonGreen
val onPrimaryDark = absoluteBlack
val primaryContainerDark = terminalMidGrey
val onPrimaryContainerDark = neonGreen
val secondaryDark = neonGreen
val onSecondaryDark = absoluteBlack
val secondaryContainerDark = terminalMidGrey
val onSecondaryContainerDark = neonGreen
val tertiaryDark = neonGreen
val onTertiaryDark = absoluteBlack
val tertiaryContainerDark = terminalMidGrey
val onTertiaryContainerDark = neonGreen
val errorDark = terminalError
val onErrorDark = absoluteBlack
val errorContainerDark = terminalErrorContainer
val onErrorContainerDark = terminalError
val backgroundDark = absoluteBlack
val onBackgroundDark = terminalOnSurface
val surfaceDark = absoluteBlack
val onSurfaceDark = terminalOnSurface
val surfaceVariantDark = terminalDarkGrey
val onSurfaceVariantDark = terminalOnSurface
val surfaceContainerLowestDark = absoluteBlack
val surfaceContainerLowDark = terminalDarkGrey
val surfaceContainerDark = terminalMidGrey
val surfaceContainerHighDark = terminalLightGrey
val surfaceContainerHighestDark = terminalLightGrey
val inverseSurfaceDark = terminalOnSurface
val inverseOnSurfaceDark = absoluteBlack
val outlineDark = terminalOutline
val outlineVariantDark = terminalMidGrey
val inversePrimaryDark = absoluteBlack
val surfaceDimDark = absoluteBlack
val surfaceBrightDark = terminalMidGrey
val scrimDark = absoluteBlack
