package com.ddzhilian.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.activity.compose.BackHandler
import com.kyant.backdrop.Backdrop

/** One entry of a long-press glass menu. */
class GlassMenuItem(val label: String, val icon: ImageVector, val danger: Boolean = false, val action: () -> Unit)


/**
 * Frosted-surface opacity of the glass fill, fixed at 40% ([GLASS_OPACITY]). Only the glass fill
 * uses it; the "reduce transparency" mode paints a solid colour instead and ignores this value.
 */
internal val LocalGlassOpacity = compositionLocalOf { GLASS_OPACITY }
/** Calls a long-press menu is anchored to, in root (window) pixel coordinates. */
class GlassMenuRequest(val anchor: Rect, val items: List<GlassMenuItem>)

/**
 * Holds the single active long-press menu. Compose UI (not a platform popup), so the menu can
 * refract the recorded layer backdrop instead of showing an opaque dropdown.
 */
class GlassMenuController {
    var request by mutableStateOf<GlassMenuRequest?>(null)
        private set

    fun show(anchor: Rect, items: List<GlassMenuItem>) {
        if (items.isNotEmpty()) request = GlassMenuRequest(anchor, items)
    }

    fun dismiss() { request = null }
}

val LocalGlassMenu = staticCompositionLocalOf { GlassMenuController() }

/** Long-press menu drawn with AndroidLiquidGlass: vibrancy + blur + lens over the page behind it. */
@Composable
internal fun GlassMenuOverlay(controller: GlassMenuController, backdrop: Backdrop, reduceTransparency: Boolean) {
    val request = controller.request ?: return
    BackHandler(true) { controller.dismiss() }
    val density = LocalDensity.current
    val screen = LocalWindowInfo.current.containerSize
    val panelWidth = 214.dp
    Box(Modifier.fillMaxSize()) {
        Box(Modifier.matchParentSize().clickable(remember { MutableInteractionSource() }, null) { controller.dismiss() })
        var measured by remember(request) { mutableStateOf(IntSize.Zero) }
        val widthPx = with(density) { panelWidth.toPx() }
        val margin = with(density) { 12.dp.toPx() }
        val gap = with(density) { 10.dp.toPx() }
        val anchor = request.anchor
        val alignEnd = anchor.center.x > screen.width / 2f
        val x = (if (alignEnd) anchor.right - widthPx else anchor.left)
            .coerceIn(margin, (screen.width - widthPx - margin).coerceAtLeast(margin))
        val below = anchor.bottom + gap
        val y = if (measured.height > 0 && below + measured.height > screen.height - margin)
            (anchor.top - gap - measured.height).coerceAtLeast(margin) else below
        Column(Modifier.graphicsLayer {
            alpha = if (measured.height > 0) 1f else 0f
            translationX = x
            translationY = y
        }.width(panelWidth).onSizeChanged { measured = it }
            .clickable(remember { MutableInteractionSource() }, null) {}
            .glass(backdrop, reduceTransparency, shape = RoundedCornerShape(24.dp))
            .padding(vertical = 6.dp)) {
            request.items.forEach { item ->
                Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp).clip(RoundedCornerShape(18.dp))
                    .clickable(role = Role.Button) { controller.dismiss(); item.action() }
                    .padding(horizontal = 12.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(item.icon, null, Modifier.size(19.dp),
                        tint = if (item.danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(item.label, Modifier.padding(start = 12.dp), fontSize = 15.sp,
                        color = if (item.danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
                }
            }
        }
    }
}

/** One tile of the composer attach panel. */
class PlusAction(val label: String, val icon: ImageVector, val enabled: Boolean = true, val action: () -> Unit)

/**
 * The panel behind the round "+" button. Same liquid glass treatment as the long-press menus, and
 * it sits directly above the composer so the two read as one control.
 */
@Composable
internal fun GlassPlusPanel(
    backdrop: Backdrop,
    reduceTransparency: Boolean,
    actions: List<PlusAction>,
    onDismiss: () -> Unit,
    // Sits directly above the composer: composer outer padding (10) + one control height + a 10dp gap.
    bottomInset: androidx.compose.ui.unit.Dp = ControlHeight + 20.dp,
) {
    BackHandler(true) { onDismiss() }
    Box(Modifier.fillMaxSize()) {
        Box(Modifier.matchParentSize().clickable(remember { MutableInteractionSource() }, null) { onDismiss() })
        // Anchored to the "+" button on the left, not the input capsule on the right.
        Row(Modifier.align(Alignment.BottomStart).navigationBarsPadding().padding(start = 12.dp, bottom = bottomInset)
            .clickable(remember { MutableInteractionSource() }, null) {}
            .glass(backdrop, reduceTransparency, shape = RoundedCornerShape(26.dp))
            .padding(horizontal = 8.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            actions.forEach { action ->
                val alpha = if (action.enabled) 1f else .38f
                Column(Modifier.width(64.dp).clip(RoundedCornerShape(20.dp))
                    .clickable(enabled = action.enabled, role = Role.Button) { onDismiss(); action.action() }
                    .padding(vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.size(46.dp).clip(RoundedCornerShape(16.dp))
                        .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = alpha)),
                        contentAlignment = Alignment.Center) {
                        Icon(action.icon, null, Modifier.size(22.dp),
                            tint = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = alpha))
                    }
                    Text(action.label, Modifier.padding(top = 7.dp), fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha))
                }
            }
        }
    }
}
