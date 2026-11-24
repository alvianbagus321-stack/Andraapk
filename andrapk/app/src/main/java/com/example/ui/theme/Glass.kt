package com.example.ui.theme

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

// ==========================================================
// LIQUID GLASS TOOLKIT (Tier-2 specular glass + ambient depth)
// Semua efek digambar lewat drawWithCache: 0 alokasi per frame,
// 0 recomposition, dan aman untuk perangkat kelas bawah (60fps).
// ==========================================================

/** Kanvas dasar obsidian untuk kedalaman ambient. */
val DepthObsidian = Color(0xFF070A10)

/** Spring taktil untuk feedback sentuh (press scale 0.97f). */
val TactileSpring = spring<Float>(dampingRatio = 0.82f, stiffness = 450f)

/** Spring lembut untuk perubahan tata letak / seleksi. */
val LayoutSpring = spring<Float>(dampingRatio = 0.86f, stiffness = 280f)

/**
 * Latar kedalaman ambient statis: dasar obsidian + 3 orb radial redup.
 * Digambar SEKALI via drawWithCache — tanpa animasi latar tak berujung.
 */
fun Modifier.ambientDepth(): Modifier = drawWithCache {
    val w = size.width
    val h = size.height
    val orbTop = Brush.radialGradient(
        colors = listOf(JarvisCyan.copy(alpha = 0.10f), Color.Transparent),
        center = Offset(w * 0.88f, h * 0.02f),
        radius = (w * 0.85f).coerceAtLeast(1f)
    )
    val orbSide = Brush.radialGradient(
        colors = listOf(Color(0xFF24395C).copy(alpha = 0.16f), Color.Transparent),
        center = Offset(w * 0.04f, h * 0.34f),
        radius = (w * 0.72f).coerceAtLeast(1f)
    )
    val orbBottom = Brush.radialGradient(
        colors = listOf(Color(0xFF131D2B).copy(alpha = 0.60f), Color.Transparent),
        center = Offset(w * 0.5f, h * 1.08f),
        radius = (w * 0.95f).coerceAtLeast(1f)
    )
    onDrawBehind {
        drawRect(DepthObsidian)
        drawRect(orbSide)
        drawRect(orbTop)
        drawRect(orbBottom)
    }
}

/**
 * Permukaan kaca spekular Tier-2:
 * - isian putih translusen (0.06–0.10f; aktif 0.11–0.15f),
 * - rim gradien terarah 0.85dp (terang kiri-atas → redup → sedang kanan-bawah),
 * - sheen atas yang memudar dalam ~22dp.
 * Seluruh brush dialokasikan sekali di cache scope.
 */
fun Modifier.specularGlass(
    cornerRadius: Dp,
    fillAlpha: Float = 0.07f,
    active: Boolean = false,
    rimBrush: Brush? = null
): Modifier = drawWithCache {
    val radiusPx = cornerRadius.toPx()
    val cr = CornerRadius(radiusPx, radiusPx)
    val strokeWidth = 0.85.dp.toPx()
    val fill = Color.White.copy(alpha = if (active) (fillAlpha + 0.05f) else fillAlpha)
    val rim = rimBrush ?: Brush.linearGradient(
        colors = listOf(
            Color.White.copy(alpha = if (active) 0.32f else 0.26f),
            Color.White.copy(alpha = 0.05f),
            Color.White.copy(alpha = 0.13f)
        ),
        start = Offset.Zero,
        end = Offset(size.width, size.height)
    )
    val sheenHeight = 22.dp.toPx().coerceAtMost(size.height)
    val sheen = Brush.verticalGradient(
        colors = listOf(Color.White.copy(alpha = 0.05f), Color.Transparent),
        startY = 0f,
        endY = sheenHeight.coerceAtLeast(1f)
    )
    val rimTopLeft = Offset(strokeWidth / 2f, strokeWidth / 2f)
    val rimSize = Size(
        (size.width - strokeWidth).coerceAtLeast(1f),
        (size.height - strokeWidth).coerceAtLeast(1f)
    )
    onDrawWithContent {
        drawRoundRect(color = fill, cornerRadius = cr)
        drawRoundRect(brush = sheen, cornerRadius = cr, size = Size(size.width, sheenHeight))
        drawContent()
        drawRoundRect(
            brush = rim,
            cornerRadius = cr,
            style = Stroke(width = strokeWidth),
            topLeft = rimTopLeft,
            size = rimSize
        )
    }
}

/**
 * Skala feedback sentuh 0.97f via spring taktil.
 * Gunakan hasilnya di dalam lambda graphicsLayer { } agar animasi
 * hanya menyentuh fase draw (tanpa recomposition/layout).
 */
@Composable
fun rememberPressScale(
    interactionSource: InteractionSource,
    pressedScale: Float = 0.97f
): State<Float> {
    val pressed by interactionSource.collectIsPressedAsState()
    return animateFloatAsState(
        targetValue = if (pressed) pressedScale else 1f,
        animationSpec = TactileSpring,
        label = "press_scale"
    )
}
