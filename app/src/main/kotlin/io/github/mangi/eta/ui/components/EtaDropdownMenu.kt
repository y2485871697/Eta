package io.github.mangi.eta.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import io.github.mangi.eta.data.model.AppearanceThemeMode
import io.github.mangi.eta.ui.app.LocalAppearanceSettings

private val MenuShape = RoundedCornerShape(20.dp)
private val MenuMinWidth = 180.dp
private val MenuMaxWidth = 280.dp

@Composable
internal fun EtaDropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    offset: DpOffset = DpOffset.Zero,
    content: @Composable ColumnScope.() -> Unit,
) {
    if (!expanded) return

    val appearance = LocalAppearanceSettings.current
    val isDark = when (appearance.themeMode) {
        AppearanceThemeMode.SYSTEM -> isSystemInDarkTheme()
        AppearanceThemeMode.LIGHT -> false
        AppearanceThemeMode.DARK -> true
    }
    val density = LocalDensity.current
    val offsetXPx = with(density) { offset.x.roundToPx() }
    val offsetYPx = with(density) { offset.y.roundToPx() }
    val positionProvider = remember(offsetXPx, offsetYPx) {
        EtaMenuPositionProvider(offsetXPx, offsetYPx)
    }

    Popup(
        onDismissRequest = onDismissRequest,
        popupPositionProvider = positionProvider,
        properties = PopupProperties(focusable = true),
    ) {
        Surface(
            modifier = modifier
                .width(IntrinsicSize.Max)
                .widthIn(min = MenuMinWidth, max = MenuMaxWidth),
            shape = MenuShape,
            color = if (isDark) {
                MaterialTheme.colorScheme.surfaceContainerHigh
            } else {
                Color.White
            },
            tonalElevation = 0.dp,
            shadowElevation = 0.dp,
            border = if (isDark) {
                BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant)
            } else {
                BorderStroke(0.5.dp, Color.Black.copy(alpha = 0.08f))
            },
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

private class EtaMenuPositionProvider(
    private val offsetXPx: Int,
    private val offsetYPx: Int,
) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val maxX = (windowSize.width - popupContentSize.width).coerceAtLeast(0)
        val x = (anchorBounds.left + offsetXPx).coerceIn(0, maxX)
        val belowY = anchorBounds.bottom + offsetYPx
        val aboveY = anchorBounds.top - popupContentSize.height - offsetYPx
        val fitsBelow = belowY + popupContentSize.height <= windowSize.height
        val y = if (fitsBelow || aboveY < 0) belowY else aboveY
        val maxY = (windowSize.height - popupContentSize.height).coerceAtLeast(0)
        return IntOffset(x, y.coerceIn(0, maxY))
    }
}