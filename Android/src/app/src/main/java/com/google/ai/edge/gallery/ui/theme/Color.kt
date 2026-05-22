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

// CLU/BOX Marathon Palette
// ─────────────────────────────────────────────────────────────────────────────
// Design language: Marathon (Bungie) – pure black canvas, acid lime accent,
// electric blue secondary, heavy borders, crosshair registration marks.
//
// Naming kept source-compatible with original call-sites.

// ── Core accent — Marathon acid lime ───────────────────────────────────────
val neonGreen          = Color(0xFFC8FF00)   // Marathon signature acid lime
val marathonBlue       = Color(0xFF1A1AE6)   // Marathon electric-blue accent
val marathonLime       = neonGreen           // alias

// ── CyberAcme extended palette ──────────────────────────────────────────────
val cyberAcmeCyan      = Color(0xFF00E5FF)   // CyberAcme terminal cyan (S'pht text)
val marathonOrange     = Color(0xFFFF6600)   // Marathon warning / danger readout
val marathonBlood      = Color(0xFF8B0000)   // Marathon rampancy blood-red
val marathonRampancyGold = Color(0xFFFFCC00) // Rampancy phase gold / alert
val cyberAcmeViolet    = Color(0xFF6A00FF)   // CyberAcme deep violet (M-class terminals)

// ── Background scale (pure black → near-black → dark grey) ─────────────────
val absoluteBlack      = Color(0xFF000000)   // page / scaffold background
val terminalDarkGrey   = Color(0xFF080808)   // surface lowest / subtle tint
val terminalMidGrey    = Color(0xFF0E0E0E)   // card / sheet surface
val terminalLightGrey  = Color(0xFF202020)   // elevated surface (container high)
val terminalOutline    = Color(0xFF303030)   // dividers & borders
val terminalOnSurface  = Color(0xFFEEEEEE)   // primary body text (off-white)

// ── Supporting greys ────────────────────────────────────────────────────────
val brutalistGrey      = Color(0xFF7A7A7A)   // secondary / disabled text
val brutalistDark      = Color(0xFF202020)   // alias for terminalLightGrey

// ── Semantic error palette ─────────────────────────────────────────────────
val terminalError          = Color(0xFFFF4444)
val terminalErrorContainer = Color(0xFF3D0000)

// ── Light-mode palette — mirrors dark (app is always dark, but Material3
//    requires both to be specified for the lightColorScheme constructor).    ──

val primaryLight                 = neonGreen
val onPrimaryLight               = absoluteBlack
val primaryContainerLight        = terminalLightGrey
val onPrimaryContainerLight      = neonGreen
val secondaryLight               = Color(0xFFB0B0B0)    // light grey secondary
val onSecondaryLight             = absoluteBlack
val secondaryContainerLight      = terminalMidGrey
val onSecondaryContainerLight    = terminalOnSurface
val tertiaryLight                = Color(0xFF909090)    // dark grey tertiary
val onTertiaryLight              = absoluteBlack
val tertiaryContainerLight       = terminalMidGrey
val onTertiaryContainerLight     = terminalOnSurface
val errorLight                   = terminalError
val onErrorLight                 = absoluteBlack
val errorContainerLight          = terminalErrorContainer
val onErrorContainerLight        = terminalError
val backgroundLight              = absoluteBlack
val onBackgroundLight            = terminalOnSurface
val surfaceLight                 = absoluteBlack
val onSurfaceLight               = terminalOnSurface
val surfaceVariantLight          = terminalDarkGrey
val onSurfaceVariantLight        = Color(0xFFCCCCCC)
val surfaceContainerLowestLight  = absoluteBlack
val surfaceContainerLowLight     = terminalDarkGrey
val surfaceContainerLight        = terminalMidGrey
val surfaceContainerHighLight    = terminalLightGrey
val surfaceContainerHighestLight = Color(0xFF333333)
val inverseSurfaceLight          = terminalOnSurface
val inverseOnSurfaceLight        = absoluteBlack
val outlineLight                 = terminalOutline
val outlineVariantLight          = terminalMidGrey
val inversePrimaryLight          = absoluteBlack
val surfaceDimLight              = absoluteBlack
val surfaceBrightLight           = terminalLightGrey
val scrimLight                   = absoluteBlack

// ── Dark-mode palette — same values for forced dark ───────────────────────

val primaryDark                 = neonGreen
val onPrimaryDark               = absoluteBlack
val primaryContainerDark        = terminalLightGrey
val onPrimaryContainerDark      = neonGreen
val secondaryDark               = Color(0xFFB0B0B0)    // light grey secondary
val onSecondaryDark             = absoluteBlack
val secondaryContainerDark      = terminalMidGrey
val onSecondaryContainerDark    = terminalOnSurface
val tertiaryDark                = Color(0xFF909090)    // dark grey tertiary
val onTertiaryDark              = absoluteBlack
val tertiaryContainerDark       = terminalMidGrey
val onTertiaryContainerDark     = terminalOnSurface
val errorDark                   = terminalError
val onErrorDark                 = absoluteBlack
val errorContainerDark          = terminalErrorContainer
val onErrorContainerDark        = terminalError
val backgroundDark              = absoluteBlack
val onBackgroundDark            = terminalOnSurface
val surfaceDark                 = absoluteBlack
val onSurfaceDark               = terminalOnSurface
val surfaceVariantDark          = terminalDarkGrey
val onSurfaceVariantDark        = Color(0xFFCCCCCC)
val surfaceContainerLowestDark  = absoluteBlack
val surfaceContainerLowDark     = terminalDarkGrey
val surfaceContainerDark        = terminalMidGrey
val surfaceContainerHighDark    = terminalLightGrey
val surfaceContainerHighestDark = Color(0xFF333333)
val inverseSurfaceDark          = terminalOnSurface
val inverseOnSurfaceDark        = absoluteBlack
val outlineDark                 = terminalOutline
val outlineVariantDark          = terminalMidGrey
val inversePrimaryDark          = absoluteBlack
val surfaceDimDark              = absoluteBlack
val surfaceBrightDark           = terminalLightGrey
val scrimDark                   = absoluteBlack
