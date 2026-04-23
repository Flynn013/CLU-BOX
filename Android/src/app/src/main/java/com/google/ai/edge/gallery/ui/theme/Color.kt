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

// CLU/BOX Monochrome Brutalist Palette
// Primary accent: white (#FFFFFF) on absolute black (#000000) with grey (#333333) accents.
// The original neon-green name is kept for source compatibility; its value has been
// updated to pure white so all existing references adopt the new brutalist look.

val neonGreen = Color(0xFFFFFFFF)          // primary accent — white (was #4ade80)
val absoluteBlack = Color(0xFF000000)       // all backgrounds
val terminalDarkGrey = Color(0xFF111111)    // subtle surface tint
val terminalMidGrey = Color(0xFF222222)     // card / surface container
val terminalLightGrey = Color(0xFF333333)   // dividers, highlights (brutalist grey)
val terminalOutline = Color(0xFF555555)     // border / outline mid-grey
val terminalOnSurface = Color(0xFFFFFFFF)   // all body text — pure white
val terminalError = Color(0xFFFF5555)       // error red (unchanged)
val terminalErrorContainer = Color(0xFF3D0000) // error container (unchanged)

// Additional explicit grey steps for fine-grained UI control.
val brutalistGrey = Color(0xFF888888)       // secondary text / disabled state
val brutalistDark = Color(0xFF333333)       // same as terminalLightGrey; canonical reference

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
