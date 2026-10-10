package io.github.mangi.eta.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Transparent chat icon action, drawn without miuix `IconButton`'s unconditional squircle layer.
 *
 * miuix builds an offscreen squircle mask for every `IconButton` even when the background is
 * transparent, so that mask only ever carved pixels which were never visible, while each node
 * still paid an extra offscreen composite per frame. One device scroll trace attributed roughly
 * three quarters of all `alpha caused saveLayer` records to these small icon buttons.
 *
 * Size, role, enablement and content match the `IconButton` call it replaces, so the hit target,
 * semantics and glyph are unchanged. Tooltips stay with the caller, exactly as before.
 */
@Composable
internal fun ChatIconAction(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    minWidth: Dp = 30.dp,
    minHeight: Dp = 30.dp,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(
        modifier = modifier
            .defaultMinSize(minWidth = minWidth, minHeight = minHeight)
            .clickable(
                enabled = enabled,
                role = Role.Button,
                indication = null,
                interactionSource = remember { MutableInteractionSource() },
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
        content = content,
    )
}
