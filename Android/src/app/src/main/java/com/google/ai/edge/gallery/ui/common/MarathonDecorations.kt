/*
 * Copyright 2026 Flynn013 / CLU/BOX
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.ai.edge.gallery.ui.common

import android.graphics.BlurMaskFilter
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * **Marathon CyberAcme Aesthetics System**
 *
 * A deep, retro-futuristic styling suite built strictly with Jetpack Compose canvas operations.
 * Simulates a classic UESC spacecraft hardware console: scanlines, phosphor bleeding, hazard lines,
 * and diagnostic vector corner marks.
 */
object MarathonTheme {
    // Monospaced Classic Terminal Palettes
    val PhosphorGreen = Color(0xFF00FF66)
    val PaleGreenGlow = Color(0x3300FF66)
    val DullTerminalGreen = Color(0xFF005511)
    
    val HazardOrange = Color(0xFFFF6600)
    val AlertRed = Color(0xFFFF2200)
    val WarningYellow = Color(0xFFFFBB00)
    
    val DeepObsidian = Color(0xFF020603)
    val SlateGray = Color(0xFF1E2A22)
    val GridLineColor = Color(0x1500FF66)
}

/**
 * Custom octagonal cybernetic corner shape.
 */
val CyberOctagonShape = GenericShape { size, _ ->
    val cut = 16f
    moveTo(cut, 0f)
    lineTo(size.width - cut, 0f)
    lineTo(size.width, cut)
    lineTo(size.width, size.height - cut)
    lineTo(size.width - cut, size.height)
    lineTo(cut, size.height)
    lineTo(0f, size.height - cut)
    lineTo(0f, cut)
    close()
}

/**
 * Draws 45-degree industrial warning stripes (Hazard/Warning look).
 *
 * @param stripeColor Colors of the warning slashes.
 * @param stripeWidth The visual thickness of each line segment.
 */
fun Modifier.hazardStripes(
    stripeColor: Color = MarathonTheme.HazardOrange.copy(alpha = 0.85f),
    stripeWidth: Dp = 12.dp,
    gapWidth: Dp = 16.dp
): Modifier = this.drawBehind {
    val stripeWidthPx = stripeWidth.toPx()
    val gapWidthPx = gapWidth.toPx()
    val totalPeriod = stripeWidthPx + gapWidthPx
    val diagonalLength = size.width + size.height

    var currentOffset = -size.height
    while (currentOffset < size.width) {
        drawLine(
            color = stripeColor,
            start = Offset(currentOffset, 0f),
            end = Offset(currentOffset + size.height, size.height),
            strokeWidth = stripeWidthPx
        )
        currentOffset += totalPeriod
    }
}

/**
 * Draws vintage CRT hardware scanlines across the component.
 *
 * @param lineAlpha Intensity of the simulated screen horizontal cathode lines.
 * @param stepPx Number of pixels between scanline sweeps.
 */
fun Modifier.crtScanlines(
    lineAlpha: Float = 0.12f,
    stepPx: Float = 8f
): Modifier = this.drawBehind {
    var y = 0f
    val strokeWidth = 1.5f
    val lineColor = Color.Black.copy(alpha = lineAlpha)
    while (y < size.height) {
        drawLine(
            color = lineColor,
            start = Offset(0f, y),
            end = Offset(size.width, y),
            strokeWidth = strokeWidth
        )
        y += stepPx
    }
}

/**
 * Emits a gorgeous outer phosphorescent radial glow shadow around the component border.
 */
fun Modifier.phosphorGlow(
    color: Color = MarathonTheme.PhosphorGreen,
    radius: Dp = 8.dp
): Modifier = this.drawBehind {
    val radiusPx = radius.toPx()
    if (radiusPx > 0f) {
        val paint = Paint().asFrameworkPaint().apply {
            this.color = color.toArgb()
            this.maskFilter = BlurMaskFilter(radiusPx, BlurMaskFilter.Blur.OUTER)
        }
        drawContext.canvas.nativeCanvas.drawRoundRect(
            0f, 0f, size.width, size.height,
            16f, 16f,
            paint
        )
    }
}

/**
 * Custom modifier that draws structural cyber-brackets, technical metrics ticks, and coordinate tick lines.
 */
fun Modifier.cyberAcmeBorder(
    borderColor: Color = MarathonTheme.PhosphorGreen,
    strokeWidth: Dp = 1.5.dp,
    bracketLength: Dp = 20.dp
): Modifier = this.drawBehind {
    val bLen = bracketLength.toPx()
    val sw = strokeWidth.toPx()
    val pathEffect = PathEffect.cornerPathEffect(4f)

    // Draw full tech box border
    drawRect(
        color = borderColor.copy(alpha = 0.25f),
        topLeft = Offset(0f, 0f),
        size = size,
        style = Stroke(width = sw / 2)
    )

    // Top-Left corner bracket
    drawPath(
        path = Path().apply {
            moveTo(0f, bLen)
            lineTo(0f, 0f)
            lineTo(bLen, 0f)
        },
        color = borderColor,
        style = Stroke(width = sw, pathEffect = pathEffect)
    )

    // Top-Right corner bracket
    drawPath(
        path = Path().apply {
            moveTo(size.width - bLen, 0f)
            lineTo(size.width, 0f)
            lineTo(size.width, bLen)
        },
        color = borderColor,
        style = Stroke(width = sw, pathEffect = pathEffect)
    )

    // Bottom-Left corner bracket
    drawPath(
        path = Path().apply {
            moveTo(0f, size.height - bLen)
            lineTo(0f, size.height)
            lineTo(bLen, size.height)
        },
        color = borderColor,
        style = Stroke(width = sw, pathEffect = pathEffect)
    )

    // Bottom-Right corner bracket
    drawPath(
        path = Path().apply {
            moveTo(size.width - bLen, size.height)
            lineTo(size.width, size.height)
            lineTo(size.width, size.height - bLen)
        },
        color = borderColor,
        style = Stroke(width = sw, pathEffect = pathEffect)
    )

    // Tactical crosshairs / Calibration ticks
    val tickSize = 6.dp.toPx()
    // Left-Edge Center Calibration Tick
    drawLine(
        color = borderColor,
        start = Offset(sw, size.height / 2),
        end = Offset(sw + tickSize, size.height / 2),
        strokeWidth = sw
    )
    // Right-Edge Center Calibration Tick
    drawLine(
        color = borderColor,
        start = Offset(size.width - sw, size.height / 2),
        end = Offset(size.width - sw - tickSize, size.height / 2),
        strokeWidth = sw
    )
}

/**
 * **CyberAcmeTerminalFrame**
 *
 * Immersive mainframe housing composable for terminal log displays or LLM cards.
 */
@Composable
fun CyberAcmeTerminalFrame(
    modifier: Modifier = Modifier,
    title: String = "DIAGNOSTIC-SYS: RUNNING",
    sectorCode: String = "SEC-09",
    borderColor: Color = MarathonTheme.PhosphorGreen,
    content: @Composable BoxScope.() -> Unit
) {
    Column(
        modifier = modifier
            .clip(CyberOctagonShape)
            .background(MarathonTheme.DeepObsidian)
            .cyberAcmeBorder(borderColor = borderColor)
            .crtScanlines()
            .padding(12.dp)
    ) {
        // Tech Header Bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Warning Dot Indicator
                Canvas(modifier = Modifier.size(8.dp)) {
                    drawCircle(color = borderColor, radius = size.minDimension / 2)
                }
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = title.uppercase(),
                    style = TextStyle(
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        fontSize = 11.sp,
                        color = borderColor,
                        letterSpacing = 1.sp
                    )
                )
            }
            Text(
                text = sectorCode.uppercase(),
                style = TextStyle(
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 10.sp,
                    color = borderColor.copy(alpha = 0.7f),
                    letterSpacing = 0.5.sp
                )
            )
        }

        // Horizontal Partition Line
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(4.dp)
                .padding(bottom = 3.dp)
        ) {
            drawLine(
                color = borderColor.copy(alpha = 0.3f),
                start = Offset(0f, 0f),
                end = Offset(size.width, 0f),
                strokeWidth = 1.5.dp.toPx()
            )
        }

        // Inner frame core wrapper
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f, fill = false),
            content = content
        )

        // Bottom status margin metadata
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "ACME-SYS-V4.11-UESC",
                style = TextStyle(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 8.sp,
                    color = borderColor.copy(alpha = 0.5f)
                )
            )
            Text(
                text = "BUFFER: NORM",
                style = TextStyle(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 8.sp,
                    color = borderColor.copy(alpha = 0.5f)
                )
            )
        }
    }
}

@Composable
fun CrosshairMark(
    modifier: Modifier = Modifier,
    size: Dp = 10.dp,
    color: Color = MarathonTheme.PhosphorGreen,
    strokeWidth: Dp = 1.dp,
) {
    Canvas(modifier = modifier.size(size)) {
        val center = Offset(this.size.width / 2f, this.size.height / 2f)
        val strokeWidthPx = strokeWidth.toPx()
        drawLine(
            color = color,
            start = Offset(0f, center.y),
            end = Offset(this.size.width, center.y),
            strokeWidth = strokeWidthPx
        )
        drawLine(
            color = color,
            start = Offset(center.x, 0f),
            end = Offset(center.x, this.size.height),
            strokeWidth = strokeWidthPx
        )
    }
}

@Composable
fun MarathonMetaBar(
    text: String,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = text,
            style = TextStyle(
                fontFamily = FontFamily.Monospace,
                fontSize = 9.sp,
                color = MarathonTheme.PhosphorGreen.copy(alpha = 0.6f),
                letterSpacing = 0.8.sp
            )
        )
    }
}

/**
 * Custom folder outline shape with octagonal corner cuts matching classic cyber HUD assets.
 */
@Composable
fun CyberFolderOutlineIcon(
    modifier: Modifier = Modifier,
    color: Color = MarathonTheme.PhosphorGreen,
    strokeWidth: Dp = 2.dp
) {
    Canvas(modifier = modifier) {
        val strokeWidthPx = strokeWidth.toPx()
        val path = Path().apply {
            val w = size.width
            val h = size.height
            val tabW = w * 0.45f
            val tabH = h * 0.28f
            val tabCut = 6.dp.toPx()
            val cornerCut = 4.dp.toPx()

            // Start at top left tab corner cut
            moveTo(tabCut, 0f)
            lineTo(tabW - tabCut, 0f)
            // Slope down to the folder top edge
            lineTo(tabW, tabH)
            // Top edge of the main folder body
            lineTo(w - cornerCut, tabH)
            // Top-right corner cut
            lineTo(w, tabH + cornerCut)
            // Right side
            lineTo(w, h - cornerCut)
            // Bottom-right corner cut
            lineTo(w - cornerCut, h)
            // Bottom edge
            lineTo(cornerCut, h)
            // Bottom-left corner cut
            lineTo(0f, h - cornerCut)
            // Left side
            lineTo(0f, tabCut)
            close()
        }
        drawPath(
            path = path,
            color = color,
            style = Stroke(width = strokeWidthPx, join = StrokeJoin.Miter)
        )
    }
}

/**
 * Stylized cyber-flower/fractal motif matching Image 5.
 */
@Composable
fun CyberFlowerIcon(
    modifier: Modifier = Modifier,
    color: Color = MarathonTheme.PhosphorGreen,
    strokeWidth: Dp = 1.5.dp
) {
    Canvas(modifier = modifier) {
        val sw = strokeWidth.toPx()
        val cx = size.width / 2f
        val cy = size.height / 2f
        val r = size.minDimension * 0.25f

        // Inner circle
        drawCircle(color = color, radius = r, style = Stroke(width = sw))

        // Central rays
        val rayCount = 8
        for (i in 0 until rayCount) {
            val angle = (i * 2 * Math.PI / rayCount).toFloat()
            val dx = Math.cos(angle.toDouble()).toFloat()
            val dy = Math.sin(angle.toDouble()).toFloat()
            drawLine(
                color = color,
                start = Offset(cx + dx * (r * 0.2f), cy + dy * (r * 0.2f)),
                end = Offset(cx + dx * (r * 0.8f), cy + dy * (r * 0.8f)),
                strokeWidth = sw
            )
        }

        // Outer brackets
        val outerR = r * 1.5f
        for (i in 0 until 4) {
            val angle = (i * Math.PI / 2).toFloat()
            val dx = Math.cos(angle.toDouble()).toFloat()
            val dy = Math.sin(angle.toDouble()).toFloat()

            val bSize = r * 0.6f
            val px = cx + dx * outerR
            val py = cy + dy * outerR

            val perpX = -dy
            val perpY = dx

            val petalPath = Path().apply {
                moveTo(px - perpX * bSize - dx * (bSize * 0.2f), py - perpY * bSize - dy * (bSize * 0.2f))
                lineTo(px, py)
                lineTo(px + perpX * bSize - dx * (bSize * 0.2f), py + perpY * bSize - dy * (bSize * 0.2f))
            }
            drawPath(path = petalPath, color = color, style = Stroke(width = sw))
        }
    }
}

/**
 * UESC diagnostics sidebar containing scrolling/ticking log rows, branding header,
 * and a triple-circle COL-NET indicator badge.
 */
@Composable
fun MarathonDiagnosticsSidebar(
    modifier: Modifier = Modifier,
    color: Color = MarathonTheme.PhosphorGreen
) {
    val logs = remember {
        listOf(
            "[SIGNAL_ALIGNED]",
            "[EMOTION HARMONIZED]",
            "COL-NET SYNCED",
            "LINK_ONLINE",
            "INITIALIZING...",
            "SHELL SYS CONNECT",
            "(MEM) BANKS 1-7 CHECK",
            "M1..................OK",
            "M2..................OK",
            "M3..................OK",
            "M4..................OK",
            "M5..................OK",
            "M6..................OK",
            "M7..................OK",
            "NN REVIEW COMPLETE",
            "VERIFYING CORES...",
            "[LINK ESTABLISHED]",
            "COL-NET SYS: NORMAL",
            "SUPPRESS INSULA DISS",
            "[SYSTEM CLEAR 1]",
            "[SYSTEM CLEAR 2]"
        )
    }

    Column(
        modifier = modifier
            .background(MarathonTheme.DeepObsidian)
            .padding(vertical = 12.dp, horizontal = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        // Top section: Custom Outline Brand
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            CyberFolderOutlineIcon(
                modifier = Modifier
                    .size(40.dp)
                    .padding(2.dp),
                color = color
            )

            // Partition Line
            Canvas(modifier = Modifier.fillMaxWidth().height(2.dp)) {
                drawLine(
                    color = color.copy(alpha = 0.2f),
                    start = Offset(0f, size.height / 2f),
                    end = Offset(size.width, size.height / 2f),
                    strokeWidth = 1.dp.toPx()
                )
            }
        }

        // Middle section: Diagnostics Log Lines
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(vertical = 8.dp, horizontal = 2.dp)
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                items(logs) { log ->
                    Text(
                        text = log.uppercase(),
                        style = TextStyle(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 6.sp,
                            color = if (log.startsWith("[")) color else color.copy(alpha = 0.45f),
                            fontWeight = FontWeight.SemiBold,
                            lineHeight = 7.sp
                        )
                    )
                }
            }
        }

        // Bottom section: COL-NET globe
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            // Partition Line
            Canvas(modifier = Modifier.fillMaxWidth().height(2.dp)) {
                drawLine(
                    color = color.copy(alpha = 0.2f),
                    start = Offset(0f, size.height / 2f),
                    end = Offset(size.width, size.height / 2f),
                    strokeWidth = 1.dp.toPx()
                )
            }

            // Triple circle globe drawing
            Canvas(modifier = Modifier.size(24.dp)) {
                val sw = 1.dp.toPx()
                val radius = size.minDimension / 3.5f
                drawCircle(color = color.copy(alpha = 0.7f), radius = radius, style = Stroke(width = sw))
                drawCircle(
                    color = color.copy(alpha = 0.35f),
                    radius = radius,
                    center = Offset(center.x - radius * 0.5f, center.y),
                    style = Stroke(width = sw)
                )
                drawCircle(
                    color = color.copy(alpha = 0.35f),
                    radius = radius,
                    center = Offset(center.x + radius * 0.5f, center.y),
                    style = Stroke(width = sw)
                )
            }

            Text(
                text = "COL-NET",
                style = TextStyle(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 7.sp,
                    color = color,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.5.sp
                )
            )
        }
    }
}
