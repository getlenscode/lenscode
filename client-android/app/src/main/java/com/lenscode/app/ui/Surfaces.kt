package com.lenscode.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

internal val PanelCorner = LensCodeCorners.Panel

internal val CardCorner = LensCodeCorners.Card

internal val DockCorner = LensCodeCorners.Control

/** Cache reflection brushes at the panel's size instead of allocating each frame. */
internal fun Modifier.glassFinish(corner: Dp, compact: Boolean = false): Modifier {
    val shape = RoundedCornerShape(corner)
    return clip(shape)
        .background(Brush.verticalGradient(
            if (compact) listOf(LensCodeColors.CardGradTop, LensCodeColors.CardBg)
            else listOf(LensCodeColors.PanelGradTop, LensCodeColors.PanelGradBottom),
        ))
        .drawWithCache {
            val glow = Brush.radialGradient(
                colors = listOf(LensCodeColors.PanelGlow, Color.Transparent),
                center = Offset(size.width * 0.12f, 0f),
                radius = (size.width * 0.65f).coerceAtLeast(1f),
            )
            val sheen = Brush.linearGradient(
                colorStops = arrayOf(
                    0f to LensCodeColors.SheenWhite,
                    0.34f to Color.Transparent,
                    0.76f to Color.Transparent,
                    1f to LensCodeColors.SheenBlue,
                ),
                start = Offset.Zero,
                end = Offset(size.width, size.height),
            )
            onDrawBehind {
                drawRect(glow)
                drawRect(sheen, blendMode = BlendMode.Screen)
            }
        }
        .border(
            1.dp,
            Brush.linearGradient(
                0f to LensCodeColors.PanelBorderTop,
                0.4f to LensCodeColors.CardBorder,
                0.75f to LensCodeColors.PanelBorder,
                1f to LensCodeColors.CardBorder,
            ),
            shape,
        )
}

/** The .panel style: gradient fill + corner glow + screen-blend sheen + top-bright border. */
@Composable
fun GlassSurface(corner: Dp = PanelCorner, content: @Composable () -> Unit) {
    Box(
        Modifier.fillMaxSize().glassFinish(corner),
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}

/** Compact glass for floating agent cards. */
@Composable
fun CardSurface(corner: Dp = CardCorner, content: @Composable () -> Unit) {
    Box(
        Modifier.fillMaxSize().glassFinish(corner, compact = true),
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}

@Composable
fun Eyebrow(text: String) {
    Text(text, color = LensCodeColors.Eyebrow, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
}

@Composable
fun StatusDot(color: Color, size: Dp = 8.dp) {
    Box(Modifier.size(size).clip(CircleShape).background(color))
}
