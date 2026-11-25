package com.example.ui.theme

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.graphicsLayer

// ==========================================================
// SMOOTH 60 FPS MOTION & SPRING PHYSICS TOKENS
// Seluruh animasi berjalan di fase draw (graphicsLayer) tanpa relayout.
// ==========================================================

/** Spring taktil untuk feedback sentuh, tombol, dan toggle (press scale 0.97f). */
val TactileSpring = spring<Float>(dampingRatio = 0.82f, stiffness = 450f)

/** Spring fluida untuk transisi tata letak, seleksi pil, dan expand/collapse. */
val FluidSpring = spring<Float>(dampingRatio = 0.86f, stiffness = 280f)

/** Alias kompatibilitas untuk FluidSpring. */
val LayoutSpring = FluidSpring

/** Durasi transisi tab ringan (fade + scale 0.985 -> 1f di graphicsLayer). */
const val TabTransitionDurationMs = 180

/**
 * Skala feedback sentuh 0.97f via spring taktil.
 * Baca nilainya di dalam lambda graphicsLayer { } agar Compose melewatkan
 * fase Composition & Layout sepenuhnya (0ms jank).
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

/**
 * Modifier draw-phase press scale untuk kartu, pil kaca, dan tombol interaktif.
 */
fun Modifier.pressScale(
    interactionSource: InteractionSource,
    pressedScale: Float = 0.97f
): Modifier = composed {
    val scaleState = rememberPressScale(interactionSource, pressedScale)
    graphicsLayer {
        val s = scaleState.value
        scaleX = s
        scaleY = s
    }
}
