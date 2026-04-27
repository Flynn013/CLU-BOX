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

// CLU/BOX Ultra-Dark Material Design Palette
// ─────────────────────────────────────────────────────────────────────────────
// Design language: pure black canvas, elevation through dark grey steps,
// clean white as the sole accent.  No colour noise.
//
// Naming kept source-compatible with the original (neonGreen, absoluteBlack…)
// so no call-sites need to change.

// ── Core accent ────────────────────────────────────────────────────────────
val neonGreen          = Color(0xFFFFFFFF)   // primary accent — pure white

// ── Background scale (pure black → near-black → dark grey) ─────────────────
val absoluteBlack      = Color(0xFF000000)   // page / scaffold background
val terminalDarkGrey   = Color(0xFF0D0D0D)   // surface lowest / subtle tint
val terminalMidGrey    = Color(0xFF1A1A1A)   // card / sheet surface
val terminalLightGrey  = Color(0xFF2A2A2A)   // elevated surface (container high)
val terminalOutline    = Color(0xFF3A3A3A)   // dividers & borders
val terminalOnSurface  = Color(0xFFECECEC)   // primary body text (off-white for comfort)

// ── Supporting greys ────────────────────────────────────────────────────────
val brutalistGrey      = Color(0xFF7A7A7A)   // secondary / disabled text
val brutalistDark      = Color(0xFF2A2A2A)   // alias for terminalLightGrey

// ── Semantic error palette (unchanged) ─────────────────────────────────────
val terminalError          = Color(0xFFFF5555)
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
