package com.ddzhilian.chat.liquid

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastCoerceIn
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
 * Liquid glass switch. Visual design ported from Kyant0/AndroidLiquidGlass 1.0.6
 * (catalog/src/main/java/com/kyant/backdrop/catalog/components/LiquidToggle.kt), which ships as
 * sample source rather than a Maven artifact. Apache-2.0, see
 * assets/licenses/AndroidLiquidGlass-LICENSE.txt.
 */

/**
 * A liquid-glass switch. The knob is a glass capsule that refracts while held, and the track colour
 * crossfades between the off and on tints as it moves.
 *
 * Changes from the upstream sample, for the same reasons as [LiquidSlider]:
 *  - Input uses Compose's standard detectors. The sample's custom `inspectDragGestures` handled the
 *    tap but ignored the drag, and its `snapshotFlow { selected() }` observer never re-emitted for a
 *    plain (non-snapshot) state field, so the knob froze while the value changed.
 *  - Both drag and tap commit on release, so a press-drag-release across the track also works.
 *
 * [selected] is read as a lambda so external state stays the single source of truth; [reduce] keeps the
 * same 64x28dp track and 40x24dp knob and only replaces the glass surface with a flat one. Swapping in
 * Material's Switch instead would change the control's shape under the user's finger on toggle.
 */
@Composable
fun LiquidToggle(
    selected: () -> Boolean,
    onSelect: (Boolean) -> Unit,
    backdrop: Backdrop,
    modifier: Modifier = Modifier,
    reduce: Boolean = false,
) {
    // Material colour roles instead of the sample's hard-coded iOS green: the "on" track follows the
    // theme's primary, the "off" track uses surfaceVariant, matching the slider's roles.
    val accentColor = MaterialTheme.colorScheme.primary
    val trackColor = MaterialTheme.colorScheme.surfaceVariant

    val density = LocalDensity.current
    val isLtr = LocalLayoutDirection.current == LayoutDirection.Ltr
    val dragWidth = with(density) { 20f.dp.toPx() }
    val animationScope = rememberCoroutineScope()
    var fraction by remember { mutableFloatStateOf(if (selected()) 1f else 0f) }
    val damped = remember(animationScope) {
        DampedDragAnimation(
            animationScope = animationScope,
            initialValue = fraction,
            valueRange = 0f..1f,
            visibilityThreshold = 0.001f,
            initialScale = 1f,
            pressedScale = 1.5f,
            onDragStarted = {},
            onDragStopped = {},
            onDrag = { _, _ -> },
        )
    }
    LaunchedEffect(damped) {
        if (damped.targetValue != fraction) damped.updateValue(fraction)
    }
    // Read the caller's state during composition. Observing it through the captured lambda instead
    // silently froze the knob, because a plain field is not snapshot state.
    val isOn = selected()
    LaunchedEffect(isOn) {
        val target = if (isOn) 1f else 0f
        if (fraction != target) {
            fraction = target
            damped.animateToValue(target)
        }
    }

    val trackBackdrop = rememberLayerBackdrop()

    Box(modifier, contentAlignment = Alignment.CenterStart) {
        Box(
            Modifier
                .layerBackdrop(trackBackdrop)
                .clip(Capsule())
                .drawBehind {
                    val f = damped.value
                    drawRect(lerp(trackColor, accentColor, f))
                }
                .size(64f.dp, 28f.dp)
        )

        Box(
            Modifier
                .graphicsLayer {
                    val padding = 2f.dp.toPx()
                    translationX =
                        if (isLtr) lerp(padding, padding + dragWidth, damped.value)
                        else lerp(-padding, -(padding + dragWidth), damped.value)
                }
                .semantics { role = Role.Switch }
                // Drag: follow the finger, then commit the nearest end on release.
                .pointerInput(dragWidth, isLtr) {
                    detectHorizontalDragGestures(
                        onDragStart = { damped.press() },
                        onDragEnd = {
                            damped.release()
                            val target = if (damped.value >= 0.5f) 1f else 0f
                            fraction = target
                            onSelect(target == 1f)
                        },
                        onDragCancel = {
                            damped.release()
                            fraction = if (isOn) 1f else 0f
                        },
                    ) { change, dragAmount ->
                        change.consume()
                        if (dragWidth <= 0f) return@detectHorizontalDragGestures
                        val delta = dragAmount / dragWidth
                        val next = (fraction + if (isLtr) delta else -delta).fastCoerceIn(0f, 1f)
                        fraction = next
                        damped.updateValue(next)
                    }
                }
                // Tap: flip. Also cancels the small drag a real finger often produces.
                .pointerInput(isOn) {
                    detectTapGestures {
                        val target = if (isOn) 0f else 1f
                        fraction = target
                        damped.animateToValue(target)
                        onSelect(target == 1f)
                    }
                }
                .then(
                    if (reduce) {
                        // Solid appearance: identical 40x24dp capsule, flat theme surface instead of glass.
                        Modifier
                            .graphicsLayer {
                                val velocity = damped.velocity / 50f
                                scaleX = damped.scaleX /
                                    (1f - (velocity * 0.75f).fastCoerceIn(-0.2f, 0.2f))
                                scaleY = damped.scaleY *
                                    (1f - (velocity * 0.25f).fastCoerceIn(-0.2f, 0.2f))
                            }
                            .clip(Capsule())
                            .background(MaterialTheme.colorScheme.surface)
                            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, Capsule())
                    } else {
                        Modifier.drawBackdrop(
                            backdrop = rememberCombinedBackdrop(
                                backdrop,
                                rememberBackdrop(trackBackdrop) { drawBackdrop ->
                                    val progress = damped.pressProgress
                                    val scaleX = lerp(2f / 3f, 0.75f, progress)
                                    val scaleY = lerp(0f, 0.75f, progress)
                                    scale(scaleX, scaleY) { drawBackdrop() }
                                },
                            ),
                            shape = { Capsule() },
                            effects = {
                                val progress = damped.pressProgress
                                blur(8f.dp.toPx() * (1f - progress))
                                lens(
                                    5f.dp.toPx() * progress,
                                    10f.dp.toPx() * progress,
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
                            shadow = {
                                Shadow(radius = 4f.dp, color = Color.Black.copy(alpha = 0.05f))
                            },
                            innerShadow = {
                                val progress = damped.pressProgress
                                InnerShadow(radius = 4f.dp * progress, alpha = progress)
                            },
                            layerBlock = {
                                scaleX = damped.scaleX
                                scaleY = damped.scaleY
                                val velocity = damped.velocity / 50f
                                scaleX /= 1f - (velocity * 0.75f).fastCoerceIn(-0.2f, 0.2f)
                                scaleY *= 1f - (velocity * 0.25f).fastCoerceIn(-0.2f, 0.2f)
                            },
                            onDrawSurface = {
                                val progress = damped.pressProgress
                                drawRect(Color.White.copy(alpha = 1f - progress))
                            },
                        )
                    }
                )
                .size(40f.dp, 24f.dp)
        )
    }
}
