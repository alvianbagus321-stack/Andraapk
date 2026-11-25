package com.example.ui.theme

import android.graphics.Rect
import android.view.ViewTreeObserver
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

// ==========================================================
// TWO-TIER HYBRID LIQUID GLASS TOOLKIT (60 FPS BUDGET)
// - Tier 1 (floatingGlassChrome): Maks 2-3 chrome melayang per layar
//   (Floating Pill Nav Bar, Floating Top Bar, Floating ChatInputBar).
// - Tier 2 (specularGlass / liquidGlassSurface): Kartu & item list
//   via drawWithCache (0ms blur cost, 0 alokasi per frame).
// ==========================================================

object GlassRadius {
    val Small: Dp = 12.dp
    val Control: Dp = 14.dp
    val Button: Dp = 14.dp
    val Card: Dp = 20.dp
    val CardLarge: Dp = 24.dp
    val Pill: Dp = 28.dp
    val Input: Dp = 28.dp
    val Sheet: Dp = 28.dp
}

object GlassSpacing {
    val ScreenHorizontal: Dp = 16.dp
    val CardPadding: Dp = 16.dp
    val SectionGap: Dp = 12.dp
    val FloatingNavClearance: Dp = 108.dp
}

/**
 * Status keyboard (IME) lintas versi Android & OEM ROM:
 * Menggabungkan Compose WindowInsets.ime (animasi 60/120fps di API 30+)
 * dengan fallback ViewTreeObserver + getWindowVisibleDisplayFrame agar kotak
 * ketik tidak pernah tertutup keyboard di API 26-29 maupun ROM ColorOS/Realme/MIUI.
 */
@Immutable
data class ReliableImeState(
    val isVisible: Boolean,
    val bottomInsetPx: Int
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun rememberReliableImeState(): ReliableImeState {
    val view = LocalView.current
    val density = LocalDensity.current

    // 1) Sumber utama: Compose WindowInsets.ime mentah (tidak bisa dibatalkan oleh consumedWindowInsets)
    val composeImeBottomPx = WindowInsets.ime.getBottom(density)
    val composeImeVisible = WindowInsets.isImeVisible

    // 2) Fallback andal: ukur area layar yang benar-benar tertutup keyboard pada view saat ini
    var legacyVisible by remember { mutableStateOf(false) }
    var legacyOverlapPx by remember { mutableIntStateOf(0) }
    var viewAlreadyResized by remember { mutableStateOf(false) }

    DisposableEffect(view) {
        val visibleRect = Rect()
        val viewLocation = IntArray(2)
        val listener = ViewTreeObserver.OnGlobalLayoutListener {
            val rootView = view.rootView ?: return@OnGlobalLayoutListener
            rootView.getWindowVisibleDisplayFrame(visibleRect)
            val rootHeight = rootView.height
            if (rootHeight <= 0) return@OnGlobalLayoutListener

            val screenDiff = (rootHeight - visibleRect.bottom).coerceAtLeast(0)
            // Ambang 15% tinggi layar membedakan keyboard dari navigation bar 3 tombol
            val imeOpen = screenDiff > (rootHeight * 0.15f).toInt()
            legacyVisible = imeOpen

            if (imeOpen) {
                view.getLocationInWindow(viewLocation)
                val viewBottom = viewLocation[1] + view.height
                val overlap = (viewBottom - visibleRect.bottom).coerceAtLeast(0)
                legacyOverlapPx = overlap
                // Jika overlap hampir nol saat keyboard terbuka, OS sudah mengecilkan tinggi view
                viewAlreadyResized = overlap < (rootHeight * 0.05f).toInt()
            } else {
                legacyOverlapPx = 0
                viewAlreadyResized = false
            }
        }
        view.viewTreeObserver.addOnGlobalLayoutListener(listener)
        onDispose {
            view.viewTreeObserver.removeOnGlobalLayoutListener(listener)
        }
    }

    val isVisible = composeImeVisible || composeImeBottomPx > 0 || legacyVisible
    val bottomInsetPx = when {
        viewAlreadyResized && legacyVisible -> 0
        composeImeBottomPx > 0 -> composeImeBottomPx
        legacyVisible -> legacyOverlapPx
        else -> 0
    }
    return ReliableImeState(isVisible = isVisible, bottomInsetPx = bottomInsetPx)
}

/**
 * Kanvas kedalaman ambient statis: dasar obsidian + 3 orb radial redup.
 * Digambar SEKALI via drawWithCache — bebas animasi latar tak berujung.
 */
fun Modifier.ambientDepth(): Modifier = drawWithCache {
    val w = size.width
    val h = size.height
    val orbTop = Brush.radialGradient(
        colors = listOf(JarvisCyan.copy(alpha = 0.13f), Color.Transparent),
        center = Offset(w * 0.86f, h * 0.04f),
        radius = (w * 0.88f).coerceAtLeast(1f)
    )
    val orbSide = Brush.radialGradient(
        colors = listOf(AmbientOrbSecondary.copy(alpha = 0.18f), Color.Transparent),
        center = Offset(w * 0.06f, h * 0.38f),
        radius = (w * 0.78f).coerceAtLeast(1f)
    )
    val orbBottom = Brush.radialGradient(
        colors = listOf(AmbientOrbBottom.copy(alpha = 0.65f), Color.Transparent),
        center = Offset(w * 0.5f, h * 1.06f),
        radius = (w * 0.96f).coerceAtLeast(1f)
    )
    onDrawBehind {
        drawRect(DepthObsidian)
        drawRect(orbSide)
        drawRect(orbTop)
        drawRect(orbBottom)
    }
}

/**
 * Tier-1 Frosted Floating Chrome (khusus Floating Pill Nav Bar, Floating Top Bar,
 * dan Floating ChatInputBar — maks 2-3 per layar).
 * Menggabungkan lapisan scrim gelap translusen, kilau refraktif atas, dan rim spekular
 * terarah di dalam drawWithCache untuk menjaga 60fps stabil di semua versi Android.
 */
fun Modifier.floatingGlassChrome(
    cornerRadius: Dp = GlassRadius.Pill,
    tintColor: Color = GlassChromeTint
): Modifier = drawWithCache {
    val radiusPx = cornerRadius.toPx()
    val cr = CornerRadius(radiusPx, radiusPx)
    val strokeWidth = 0.9.dp.toPx()
    val topGlowHeight = 24.dp.toPx().coerceAtMost(size.height)
    val topSheen = Brush.verticalGradient(
        colors = listOf(Color.White.copy(alpha = 0.09f), Color.Transparent),
        startY = 0f,
        endY = topGlowHeight.coerceAtLeast(1f)
    )
    val rim = Brush.linearGradient(
        colors = listOf(
            GlassRimActive,
            GlassRimMid,
            GlassRimCatch
        ),
        start = Offset.Zero,
        end = Offset(size.width, size.height)
    )
    val rimTopLeft = Offset(strokeWidth / 2f, strokeWidth / 2f)
    val rimSize = Size(
        (size.width - strokeWidth).coerceAtLeast(1f),
        (size.height - strokeWidth).coerceAtLeast(1f)
    )
    onDrawWithContent {
        drawRoundRect(color = tintColor, cornerRadius = cr)
        drawRoundRect(brush = topSheen, cornerRadius = cr, size = Size(size.width, topGlowHeight))
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
 * Tier-2 Permukaan Kaca Spekular (0ms biaya blur — aman di dalam LazyColumn):
 * - isian warna kaca translusen opsional (fillColor) atau putih translusen (fillAlpha),
 * - rim gradien terarah 0.85dp (terang kiri-atas -> redup -> kanan-bawah),
 * - inner top sheen memudar dalam ~22dp.
 */
fun Modifier.specularGlass(
    cornerRadius: Dp = GlassRadius.Card,
    fillAlpha: Float = 0.07f,
    active: Boolean = false,
    rimBrush: Brush? = null,
    fillColor: Color? = null
): Modifier = drawWithCache {
    val radiusPx = cornerRadius.toPx()
    val cr = CornerRadius(radiusPx, radiusPx)
    val strokeWidth = 0.85.dp.toPx()
    val overlayFill = Color.White.copy(alpha = if (active) (fillAlpha + 0.05f) else fillAlpha)
    val rim = rimBrush ?: Brush.linearGradient(
        colors = listOf(
            if (active) GlassRimActive else GlassRimBright,
            GlassRimMid,
            GlassRimCatch
        ),
        start = Offset.Zero,
        end = Offset(size.width, size.height)
    )
    val sheenHeight = 22.dp.toPx().coerceAtMost(size.height)
    val sheen = Brush.verticalGradient(
        colors = listOf(GlassSheenTop, Color.Transparent),
        startY = 0f,
        endY = sheenHeight.coerceAtLeast(1f)
    )
    val rimTopLeft = Offset(strokeWidth / 2f, strokeWidth / 2f)
    val rimSize = Size(
        (size.width - strokeWidth).coerceAtLeast(1f),
        (size.height - strokeWidth).coerceAtLeast(1f)
    )
    onDrawWithContent {
        if (fillColor != null) {
            drawRoundRect(color = fillColor, cornerRadius = cr)
        } else {
            drawRoundRect(color = overlayFill, cornerRadius = cr)
        }
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
 * Alias semantik untuk permukaan Tier-2 Liquid Glass.
 */
fun Modifier.liquidGlassSurface(
    cornerRadius: Dp = GlassRadius.Card,
    fillAlpha: Float = 0.07f,
    active: Boolean = false,
    rimBrush: Brush? = null,
    fillColor: Color? = null
): Modifier = specularGlass(
    cornerRadius = cornerRadius,
    fillAlpha = fillAlpha,
    active = active,
    rimBrush = rimBrush,
    fillColor = fillColor
)
