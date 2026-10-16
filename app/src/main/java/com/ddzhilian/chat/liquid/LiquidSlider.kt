package com.ddzhilian.chat.liquid

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastCoerceIn
import androidx.compose.ui.util.fastRoundToInt
import androidx.compose.ui.util.lerp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberBackdrop
import com.kyant.backdrop.backdrops.rememberCombinedBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.InnerShadow
import com.kyant.backdrop.shadow.Shadow
import com.kyant.shapes.Capsule

/*
 * Liquid glass slider. Visual design ported from Kyant0/AndroidLiquidGlass 1.0.6
 * (catalog/src/main/java/com/kyant/backdrop/catalog/components/LiquidSlider.kt), which ships as
 * sample source rather than a Maven artifact. Apache-2.0, see
 * assets/licenses/AndroidLiquidGlass-LICENSE.txt.
 */

/** Touch height of the slider. The drawn track is only 6dp, so the gesture target is much taller. */
private val SliderTouchHeight = 44.dp

/**
 * A liquid-glass slider. The thumb is a small glass capsule that grows and refracts while held, and
 * the fill/thumb ease toward the value instead of snapping to the finger.
 *
 * Two deliberate changes from the upstream sample, both about touch behaviour:
 *  - Input uses Compose's standard [detectHorizontalDragGestures] / [detectTapGestures]. The sample's
 *    custom `inspectDragGestures` accepted a tap on the track but ignored the drag, so the control
 *    looked stuck when dragged.
 *  - The gesture surface spans the full width and is [SliderTouchHeight] tall, instead of only the
 *    40x24dp thumb sitting on a 6dp track. A thumb-sized target is impractical on a phone.
 *
 * [value] is read as a lambda so external state stays the single source of truth; [onValueChange] is
 * called continuously during the drag and once at the end.
 */
@Composable
fun LiquidSlider(
    value: () -> Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    visibilityThreshold: Float,
    backdrop: Backdrop,
    modifier: Modifier = Modifier,
    // Solid accessibility appearance: same geometry, the glass surface is replaced by a flat one. This
    // deliberately does NOT swap in Material's Slider, because that would change the shape and size of
    // the control under the user's finger the moment the setting is toggled.
    reduce: Boolean = false,
) {
    // Material colour roles instead of the sample's hard-coded iOS blue, following the Material slider
    // spec: the active track takes the theme's primary and the inactive track takes surfaceVariant.
    // (This app does not enable dynamic colour, so primary is the seeded Pine.)
    val accentColor = MaterialTheme.colorScheme.primary
    val trackColor = MaterialTheme.colorScheme.surfaceVariant

    val trackBackdrop = rememberLayerBackdrop()
    val currentValue = rememberUpdatedState(value)

    BoxWithConstraints(modifier.fillMaxWidth(), contentAlignment = Alignment.CenterStart) {
        val trackWidth = constraints.maxWidth

        val isLtr = LocalLayoutDirection.current == LayoutDirection.Ltr
        val animationScope = rememberCoroutineScope()
        // Drives the press highlight and the damped follow of the thumb; the value itself is applied
        // directly from the gesture callbacks below so a drag always tracks the finger.
        val damped = remember(animationScope, valueRange) {
            DampedDragAnimation(
                animationScope = animationScope,
                initialValue = value(),
                valueRange = valueRange,
                visibilityThreshold = visibilityThreshold,
                initialScale = 1f,
                pressedScale = 1.5f,
                onDragStarted = {},
                onDragStopped = {},
                onDrag = { _, _ -> },
            )
        }
        // Read the value during composition so this effect always sees the caller's current state.
        // Observing it through the captured lambda instead (snapshotFlow { value() }) silently froze the
        // thumb: `s.glassOpacity` is a plain field, not snapshot state, so the flow emitted the initial
        // value once and never again. The number changed while the fill and thumb stayed put; only a tap
        // moved them, because the tap path drives the animation directly.
        val current = value()
        LaunchedEffect(current) {
            if (damped.targetValue != current) damped.updateValue(current)
        }

        fun span(): Float = valueRange.endInclusive - valueRange.start

        // Gesture surface: full width, finger-sized height, so press-and-drag works anywhere on it.
        Box(
            Modifier
                .fillMaxWidth()
                .height(SliderTouchHeight)
                // Drag: applies each move as a delta on the latest value, so it tracks the finger even
                // while the animation is still catching up.
                .pointerInput(trackWidth, isLtr, valueRange) {
                    detectHorizontalDragGestures(
                        onDragStart = { damped.press() },
                        onDragEnd = { damped.release() },
                        onDragCancel = { damped.release() },
                    ) { change, dragAmount ->
                        change.consume()
                        if (trackWidth <= 0) return@detectHorizontalDragGestures
                        val delta = span() * (dragAmount / trackWidth)
                        val next = (currentValue.value() + if (isLtr) delta else -delta)
                            .coerceIn(valueRange)
                        onValueChange(next)
                    }
                }
                // Tap: jump straight to the pressed position.
                .pointerInput(trackWidth, isLtr, valueRange) {
                    detectTapGestures { position ->
                        if (trackWidth <= 0) return@detectTapGestures
                        val delta = span() * (position.x / trackWidth)
                        val target = (if (isLtr) valueRange.start + delta else valueRange.endInclusive - delta)
                            .coerceIn(valueRange)
                        damped.animateToValue(target)
                        onValueChange(target)
                    }
                }
        ) {
            Box(
                Modifier
                    .align(Alignment.CenterStart)
                    .layerBackdrop(trackBackdrop)
                    .fillMaxWidth()
            ) {
                Box(
                    Modifier
                        .clip(Capsule())
                        .background(trackColor)
                        .height(6f.dp)
                        .fillMaxWidth()
                )

                Box(
                    Modifier
                        .clip(Capsule())
                        .background(accentColor)
                        .height(6f.dp)
                        .layout { measurable, constraints ->
                            val placeable = measurable.measure(constraints)
                            val width = (constraints.maxWidth * damped.progress).fastRoundToInt()
                            layout(width, placeable.height) { placeable.place(0, 0) }
                        }
                )
            }

            Box(
                Modifier
                    .align(Alignment.CenterStart)
                    .graphicsLayer {
                        translationX =
                            (-size.width / 2f + trackWidth * damped.progress)
                                .fastCoerceIn(-size.width / 4f, trackWidth - size.width * 3f / 4f) *
                                if (isLtr) 1f else -1f
                    }
                    // Same 40x24dp capsule either way; only the surface changes. Solid mode paints the
                    // theme surface (plus a hairline border so it still reads as a control on the track).
                    .then(
                        if (reduce) Modifier
                            .clip(Capsule())
                            .background(MaterialTheme.colorScheme.surface)
                            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, Capsule())
                        else Modifier.drawBackdrop(
                            backdrop = rememberCombinedBackdrop(
                                backdrop,
                                rememberBackdrop(trackBackdrop) { drawBackdrop ->
                                    val progress = damped.pressProgress
                                    val scaleX = lerp(2f / 3f, 1f, progress)
                                    val scaleY = lerp(0f, 1f, progress)
                                    scale(scaleX, scaleY) { drawBackdrop() }
                                },
                            ),
                            shape = { Capsule() },
                            effects = {
                                val progress = damped.pressProgress
                                blur(8f.dp.toPx() * (1f - progress))
                                lens(
                                    10f.dp.toPx() * progress,
                                    14f.dp.toPx() * progress,
                                    chromaticAberration = true,
                                )
                            },
                            highlight = {
                                val progress = damped.pressProgress
                                Highlight.Ambient.copy(
                                    width = Highlight.Ambient.width / 1.5f,
                                    blurRadius = Highlight.Ambient.blurRadius / 1.5f,
                                    alpha = progress,
                                )
                            },
                            shadow = { Shadow(radius = 4f.dp, color = Color.Black.copy(alpha = 0.05f)) },
                            innerShadow = {
                                val progress = damped.pressProgress
                                InnerShadow(radius = 4f.dp * progress, alpha = progress)
                            },
                            layerBlock = {
                                scaleX = damped.scaleX
                                scaleY = damped.scaleY
                                val velocity = damped.velocity / 10f
                                scaleX /= 1f - (velocity * 0.75f).fastCoerceIn(-0.2f, 0.2f)
                                scaleY *= 1f - (velocity * 0.25f).fastCoerceIn(-0.2f, 0.2f)
                            },
                            onDrawSurface = {
                                val progress = damped.pressProgress
                                drawRect(Color.White.copy(alpha = 1f - progress))
                            },
                        )
                    )
                    .size(40f.dp, 24f.dp)
            )
        }
    }
}
