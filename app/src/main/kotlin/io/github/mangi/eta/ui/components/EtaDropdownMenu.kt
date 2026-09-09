package io.github.mangi.eta.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import top.yukonga.miuix.kmp.theme.MiuixTheme

private val MenuShape = RoundedCornerShape(20.dp)
private val MenuMinWidth = 180.dp
private val MenuMaxWidth = 280.dp
private val MenuScreenMargin = 12.dp

@Composable
internal fun EtaDropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    offset: DpOffset = DpOffset.Zero,
    alignEnd: Boolean = false,
    preferAbove: Boolean = false,
    minWidth: Dp = MenuMinWidth,
    maxWidth: Dp = MenuMaxWidth,
    focusable: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    if (!expanded) return

    val colors = MiuixTheme.colorScheme
    val density = LocalDensity.current
    val offsetXPx = with(density) { offset.x.roundToPx() }
    val offsetYPx = with(density) { offset.y.roundToPx() }
    val screenMarginPx = with(density) { MenuScreenMargin.roundToPx() }
    val positionProvider = remember(offsetXPx, offsetYPx, alignEnd, preferAbove, screenMarginPx) {
        EtaMenuPositionProvider(offsetXPx, offsetYPx, alignEnd, preferAbove, screenMarginPx)
    }

    Popup(
        onDismissRequest = onDismissRequest,
        popupPositionProvider = positionProvider,
        properties = PopupProperties(
            focusable = focusable,
            dismissOnBackPress = focusable,
            dismissOnClickOutside = true,
        ),
    ) {
        CompositionLocalProvider(LocalContentColor provides colors.onSurface) {
            Surface(
                modifier = modifier
                    .width(IntrinsicSize.Max)
                    .widthIn(min = minWidth, max = maxWidth),
                shape = MenuShape,
                color = colors.surfaceContainer,
                contentColor = colors.onSurface,
                tonalElevation = 0.dp,
                shadowElevation = 0.dp,
                border = BorderStroke(0.5.dp, colors.outline.copy(alpha = 0.45f)),
            ) {
                Column(
                    modifier = Modifier
                        .padding(vertical = 6.dp)
                        .verticalScroll(rememberScrollState()),
                    content = content,
                )
            }
        }
    }
}

private class EtaMenuPositionProvider(
    private val offsetXPx: Int,
    private val offsetYPx: Int,
    private val alignEnd: Boolean,
    private val preferAbove: Boolean,
    private val screenMarginPx: Int,
) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val margin = screenMarginPx
        val maxX = (windowSize.width - popupContentSize.width - margin).coerceAtLeast(margin)
        val rawX = if (alignEnd) {
            anchorBounds.right - popupContentSize.width + offsetXPx
        } else {
            anchorBounds.left + offsetXPx
        }
        val x = rawX.coerceIn(margin, maxX)
        val belowY = anchorBounds.bottom + offsetYPx
        val aboveY = anchorBounds.top - popupContentSize.height - offsetYPx
        val fitsBelow = belowY + popupContentSize.height + margin <= windowSize.height
        val fitsAbove = aboveY >= margin
        val y = when {
            preferAbove && fitsAbove -> aboveY
            fitsBelow && !preferAbove -> belowY
            fitsAbove -> aboveY
            else -> belowY
        }
        val maxY = (windowSize.height - popupContentSize.height - margin).coerceAtLeast(margin)
        return IntOffset(x, y.coerceIn(margin, maxY))
    }
}
