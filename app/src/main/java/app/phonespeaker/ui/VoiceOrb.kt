@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package app.phonespeaker.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.graphics.shapes.Morph
import androidx.graphics.shapes.RoundedPolygon

/**
 * The assistant's face: a shape that morphs with what it is doing (waiting, listening, thinking,
 * speaking) and swells with the microphone level.
 */
@Composable
fun VoiceOrb(phase: String, micLevel: Int, modifier: Modifier = Modifier, size: Dp = 200.dp) {
    val target: RoundedPolygon = when (phase) {
        "waiting" -> MaterialShapes.Cookie9Sided
        "listening" -> MaterialShapes.Sunny
        "thinking" -> MaterialShapes.SoftBurst
        "speaking" -> MaterialShapes.Flower
        else -> MaterialShapes.Circle
    }
    val scheme = MaterialTheme.colorScheme
    val container = when (phase) {
        "waiting" -> scheme.secondaryContainer
        "listening" -> scheme.primaryContainer
        "thinking" -> scheme.tertiaryContainer
        "speaking" -> scheme.primary
        "error" -> scheme.errorContainer
        else -> scheme.surfaceContainerHigh
    }
    val onContainer = when (phase) {
        "waiting" -> scheme.onSecondaryContainer
        "listening" -> scheme.onPrimaryContainer
        "thinking" -> scheme.onTertiaryContainer
        "speaking" -> scheme.onPrimary
        "error" -> scheme.onErrorContainer
        else -> scheme.onSurfaceVariant
    }
    val color by animateColorAsState(container, MaterialTheme.motionScheme.defaultEffectsSpec(), label = "orb color")
    val iconColor by animateColorAsState(onContainer, MaterialTheme.motionScheme.defaultEffectsSpec(), label = "orb icon")

    // morph from the shape we were showing to the new one, with the expressive spring
    var from by remember { mutableStateOf(target) }
    var to by remember { mutableStateOf(target) }
    val progress = remember { Animatable(1f) }
    val spring = spring<Float>(dampingRatio = 0.9f, stiffness = Spring.StiffnessMediumLow)
    LaunchedEffect(target) {
        if (target !== to) {
            from = to
            to = target
            progress.snapTo(0f)
            progress.animateTo(1f, spring)
        }
    }
    val morph = remember(from, to) { Morph(from.normalized(), to.normalized()) }

    val spin = rememberInfiniteTransition(label = "orb spin")
    val seconds = when (phase) {
        "thinking" -> 6
        "waiting" -> 24
        "listening" -> 14
        else -> 40
    }
    val angle by spin.animateFloat(
        0f, 360f,
        infiniteRepeatable(tween(seconds * 1000, easing = LinearEasing), RepeatMode.Restart),
        label = "orb angle",
    )
    val swell by animateFloatAsState(
        1f + micLevel.coerceIn(0, 100) / 100f * 0.12f,
        // follows the microphone level, which changes many times a second: no bounce here
        spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMedium),
        label = "orb swell",
    )

    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .size(size)
                .graphicsLayer { rotationZ = angle; scaleX = swell; scaleY = swell }
                .clip(MorphShape(morph, progress.value.coerceIn(0f, 1f)))
                .background(color),
        )
        Icon(AppIcons.Mic, contentDescription = null, tint = iconColor, modifier = Modifier.size(size * 0.3f))
    }
}

/** A shape that is [progress] of the way from one polygon to another (the polygons are normalized to a unit box). */
private class MorphShape(private val morph: Morph, private val progress: Float) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val path = Path()
        var first = true
        for (cubic in morph.asCubics(progress)) {
            if (first) {
                path.moveTo(cubic.anchor0X * size.width, cubic.anchor0Y * size.height)
                first = false
            }
            path.cubicTo(
                cubic.control0X * size.width, cubic.control0Y * size.height,
                cubic.control1X * size.width, cubic.control1Y * size.height,
                cubic.anchor1X * size.width, cubic.anchor1Y * size.height,
            )
        }
        path.close()
        return Outline.Generic(path)
    }
}
