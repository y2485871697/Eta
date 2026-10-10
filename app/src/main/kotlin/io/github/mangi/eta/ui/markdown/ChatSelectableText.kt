package io.github.mangi.eta.ui.markdown

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString

/** No clickable child overlays: SelectionContainer owns long press and dragging. */
@Composable
internal fun ChatSelectableText(
    text: AnnotatedString,
    style: TextStyle,
    modifier: Modifier = Modifier,
    onTextLayout: (TextLayoutResult) -> Unit = {},
    softWrap: Boolean = true,
) {
    val links = remember(text) { text.getLinkAnnotations(0, text.length) }
    val selectable = remember(text) { selectableLinkText(text) }
    val layout = remember { arrayOfNulls<TextLayoutResult>(1) }
    val handler = LocalUriHandler.current
    fun open(link: LinkAnnotation) {
        if (link.linkInteractionListener != null) link.linkInteractionListener!!.onClick(link)
        else if (link is LinkAnnotation.Url) handler.openUri(link.url)
    }
    BasicText(
        text = selectable,
        style = style,
        softWrap = softWrap,
        modifier = modifier.then(if (links.isEmpty()) Modifier else Modifier
            .semantics {
                customActions = links.map { range ->
                    CustomAccessibilityAction("打开 ${text.text.substring(range.start, range.end)}") {
                        open(range.item)
                        true
                    }
                }
            }
            .pointerInput(text, handler) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    var moved = false
                    var end = down
                    do {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        end = event.changes.firstOrNull { it.id == down.id } ?: break
                        if ((end.position - down.position).getDistance() > viewConfiguration.touchSlop || event.changes.size > 1) moved = true
                    } while (end.pressed)
                    if (!end.pressed && !moved && end.uptimeMillis - down.uptimeMillis < viewConfiguration.longPressTimeoutMillis) {
                        layout[0]?.let { result ->
                            val offset = result.getOffsetForPosition(end.position)
                            val hit = links.firstOrNull { offset >= it.start && offset < it.end }
                            if (hit != null && result.getBoundingBox(offset).contains(end.position)) open(hit.item)
                        }
                    }
                    // Never consume the down/up: selection, scrolling and toolbar must see them.
                }
            }),
        onTextLayout = { layout[0] = it; onTextLayout(it) },
    )
}

internal fun selectableLinkText(text: AnnotatedString): AnnotatedString = buildAnnotatedString {
    append(text.text)
    text.spanStyles.forEach { addStyle(it.item, it.start, it.end) }
    text.paragraphStyles.forEach { addStyle(it.item, it.start, it.end) }
    text.getStringAnnotations(0, text.length).forEach { addStringAnnotation(it.tag, it.item, it.start, it.end) }
    text.getLinkAnnotations(0, text.length).forEach { range ->
        range.item.styles?.style?.let { addStyle(it, range.start, range.end) }
    }
}
