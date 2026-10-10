package com.ddzhilian.chat

import android.animation.ValueAnimator
import android.os.Build
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Chat
import androidx.compose.material.icons.rounded.Devices
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.backdrops.rememberCombinedBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/** One floating selection capsule. Drag previews a destination; only release changes the page. */
@Composable
internal fun LiquidChatDock(
    selectedIndex: Int,
    onSelected: (Int) -> Unit,
    backdrop: Backdrop,
    reduceTransparency: Boolean,
    modifier: Modifier = Modifier,
) {
    val tabs = remember { listOf("消息" to Icons.AutoMirrored.Rounded.Chat, "设备" to Icons.Rounded.Devices, "设置" to Icons.Rounded.Tune) }
    val count = tabs.size
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    fun physical(index: Int) = if (rtl) count - 1 - index else index
    fun logical(index: Int) = if (rtl) count - 1 - index else index
    val currentSelection by rememberUpdatedState(selectedIndex)
    val onSelect by rememberUpdatedState(onSelected)
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val position = remember(rtl) { Animatable(physical(selectedIndex).toFloat()) }
    var motionTarget by remember(rtl) { mutableIntStateOf(physical(selectedIndex)) }
    var motionJob by remember { mutableStateOf<Job?>(null) }
    var dimensions by remember { mutableStateOf(IntSize.Zero) }
    var pressed by remember { mutableStateOf(false) }
    var dragging by remember { mutableStateOf(false) }
    var dragPosition by remember { mutableFloatStateOf(0f) }
    var preview by remember { mutableIntStateOf(selectedIndex) }
    var stretch by remember { mutableFloatStateOf(0f) }
    var motionEnabled by remember { mutableStateOf(ValueAnimator.areAnimatorsEnabled()) }
    val lift by animateFloatAsState(if (pressed && motionEnabled) 1f else 0f,
        spring(dampingRatio = 1f, stiffness = 650f), label = "dock-press")
    val elongation by animateFloatAsState(if (dragging && motionEnabled) stretch else 0f,
        spring(dampingRatio = 1f, stiffness = 800f), label = "dock-stretch")
    val visibleIndex = if (pressed) preview else selectedIndex
    val slotWidth = dimensions.width.toFloat() / count

    fun moveTo(target: Int, velocity: Float = 0f, fromDrag: Boolean = false) {
        motionJob?.cancel()
        motionTarget = target
        // Copy the last directly-tracked position before releasing the finger; no one-frame jump.
        motionJob = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            if (fromDrag) position.snapTo(dragPosition)
            dragging = false
            if (!ValueAnimator.areAnimatorsEnabled()) position.snapTo(target.toFloat())
            else position.animateTo(target.toFloat(), spring(dampingRatio = if (fromDrag) .85f else 1f, stiffness = 420f),
                initialVelocity = velocity.coerceIn(-8f, 8f))
        }
    }
    val animateTo by rememberUpdatedState(::moveTo)
    LaunchedEffect(selectedIndex, rtl) {
        if (!pressed && motionTarget != physical(selectedIndex)) animateTo(physical(selectedIndex), 0f, false)
    }
    DisposableEffect(Unit) { onDispose { motionJob?.cancel() } }

    // Keep the track still: the raised selection, not the entire dock, breaks its silhouette.
    val trackInset = 7.dp
    val trackBackdrop = rememberLayerBackdrop()
    val selectionBackdrop = rememberCombinedBackdrop(backdrop, trackBackdrop)
    // drawBackdrop clips its own content: keep the track and raised selection as siblings.
    Box(modifier) {
        Box(Modifier.matchParentSize().layerBackdrop(trackBackdrop)
            .glass(backdrop, reduceTransparency, shape = RoundedCornerShape(50)))
        Box(Modifier.padding(trackInset).fillMaxWidth().onSizeChanged { dimensions = it }.selectableGroup()
            .pointerInput(count, rtl, dimensions) {
                if (dimensions.width == 0) return@pointerInput
                val width = dimensions.width.toFloat()
                val height = dimensions.height.toFloat()
                val slot = width / count
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    // The parent owns touch gestures; child selectable semantics still support keyboard/TalkBack.
                    down.consume()
                    motionEnabled = ValueAnimator.areAnimatorsEnabled()
                    val downSlot = (down.position.x / slot).toInt().coerceIn(0, count - 1)
                    val wasInsideCapsule = abs(down.position.x / slot - (position.value + .5f)) <= .5f
                    val grabOffset = if (wasInsideCapsule) down.position.x - (position.value + .5f) * slot else 0f
                    val tracker = VelocityTracker().apply { addPosition(down.uptimeMillis, down.position) }
                    var target = downSlot
                    var accepted = false
                    var releasedVelocity = 0f
                    var tracking = false
                    pressed = true
                    preview = logical(downSlot)
                    animateTo(downSlot, 0f, false)
                    try {
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (event.changes.any { it.id != down.id && it.pressed }) { change.consume(); break }
                            if (change.isConsumed) break
                            tracker.addPosition(change.uptimeMillis, change.position)
                            val delta = change.position - down.position
                            // A vertical escape or leaving the dock cancels, rather than selecting an accidental tab.
                            if ((!tracking && abs(delta.y) > viewConfiguration.touchSlop && abs(delta.y) > abs(delta.x)) ||
                                change.position.y < -height * .45f || change.position.y > height * 1.45f ||
                                change.position.x < -slot * .4f || change.position.x > width + slot * .4f) {
                                change.consume(); break
                            }
                            if (!tracking && abs(delta.x) > viewConfiguration.touchSlop) {
                                motionJob?.cancel()
                                tracking = true
                                dragging = true
                                haptics.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate)
                            }
                            if (tracking) {
                                val raw = (change.position.x - grabOffset) / slot - .5f
                                val clamped = raw.coerceIn(0f, (count - 1).toFloat())
                                // Soft edge resistance is bounded to 0.12 slots and never spills outside the screen.
                                dragPosition = clamped + ((raw - clamped) * .16f).coerceIn(-.12f, .12f)
                                target = clamped.roundToInt()
                                stretch = (abs(dragPosition - position.value) * .035f).coerceAtMost(.08f)
                                val next = logical(target)
                                if (preview != next) { preview = next; haptics.performHapticFeedback(HapticFeedbackType.SegmentTick) }
                            }
                            change.consume()
                            if (!change.pressed) {
                                // Snap to the destination under the finger, not a velocity-projected unrelated tab.
                                accepted = true
                                releasedVelocity = tracker.calculateVelocity().x / slot
                                break
                            }
                        }
                    } finally {
                        val destination = if (accepted) target else physical(currentSelection)
                        pressed = false
                        stretch = 0f
                        animateTo(destination, if (accepted && tracking) releasedVelocity else 0f, tracking)
                        if (accepted) {
                            if (logical(destination) != currentSelection) haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                            onSelect(logical(destination))
                        }
                    }
                }
            }) {
            val tint = MaterialTheme.colorScheme.primary
            // Positioned in physical coordinates; layout direction is handled by physical()/logical().
            Box(Modifier.matchParentSize(), contentAlignment = AbsoluteAlignment.TopLeft) {
                val capsuleShape = RoundedCornerShape(50)
                val capsuleMaterial = if (reduceTransparency || Build.VERSION.SDK_INT < 33) {
                    Modifier.background(tint.copy(alpha = .16f), capsuleShape)
                } else Modifier.drawBackdrop(
                    backdrop = selectionBackdrop, shape = { capsuleShape }, shadow = null,
                    effects = { blur(2.dp.toPx()); lens(12.dp.toPx() * lift, 20.dp.toPx() * lift, chromaticAberration = true) },
                    onDrawSurface = { drawRect(tint.copy(alpha = .13f - .08f * lift)) },
                )
                Box(Modifier.width(with(androidx.compose.ui.platform.LocalDensity.current) { slotWidth.toDp() })
                    .fillMaxHeight().padding(horizontal = 2.dp).graphicsLayer {
                        val p = if (dragging) dragPosition else position.value
                        translationX = p * slotWidth
                        scaleX = 1f + .10f * lift + elongation
                        // Clear BOTH track insets, then extend 10 dp past each outer edge.
                        // Compute against actual content height so this also works with large fonts.
                        val extraHeight = (trackInset * 2 + 20.dp).toPx()
                        scaleY = 1f + (extraHeight / size.height.coerceAtLeast(1f)) * lift
                    }.then(capsuleMaterial))
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                tabs.forEachIndexed { index, (label, icon) ->
                    val active = visibleIndex == index
                    val iconLift by animateFloatAsState(if (active && pressed && motionEnabled) 1f else 0f,
                        spring(dampingRatio = 1f, stiffness = 650f), label = "dock-icon-$index")
                    Column(Modifier.weight(1f).clip(RoundedCornerShape(50))
                        .selectable(selected = selectedIndex == index, role = Role.Tab, onClick = { onSelect(index) })
                        .heightIn(min = 56.dp).padding(vertical = 10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(icon, null, Modifier.size(22.dp).graphicsLayer {
                            scaleX = 1f + .12f * iconLift; scaleY = scaleX
                            translationY = -2.dp.toPx() * iconLift
                        }, tint = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(label, fontSize = 11.sp, fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                            color = MaterialTheme.colorScheme.onSurface)
                    }
                }
            }
        }
    }
}
