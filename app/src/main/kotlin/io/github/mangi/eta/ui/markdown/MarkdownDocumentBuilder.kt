package io.github.mangi.eta.ui.markdown

import androidx.compose.ui.text.AnnotatedString
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.flavours.gfm.GFMElementTypes
import org.intellij.markdown.flavours.gfm.GFMTokenTypes
import org.intellij.markdown.parser.LinkMap

/**
 * 把 GFM AST 转成 [MarkdownDocument]。
 *
 * 同一会话内连续构建时，源码切片未变的顶层块直接复用上一次的实例：流式输出只改动
 * 尾部块，前面的段落、表格与代码块既不重新生成 AnnotatedString，组合期也因实例相同
 * 而跳过。行内样式或链接表变化（主题切换、终态补齐引用链接）时缓存整体失效。
 */
internal class MarkdownDocumentBuilder {
    private var cache = emptyMap<Int, CachedBlock>()
    private var cacheStyle: MarkdownInlineStyle? = null
    private var cacheLinks: LinkMap? = null

    fun build(
        root: ASTNode,
        source: String,
        links: LinkMap?,
        style: MarkdownInlineStyle,
    ): MarkdownDocument {
        if (style != cacheStyle || links !== cacheLinks) {
            cache = emptyMap()
            cacheStyle = style
            cacheLinks = links
        }
        val converter = BlockConverter(source, links, style)
        val nextCache = HashMap<Int, CachedBlock>()
        val blocks = root.children.mapNotNull { node ->
            val slice = source.substring(node.startOffset, node.endOffset)
            val reused = cache[node.startOffset]?.takeIf { it.source == slice }?.block
            val block = reused ?: converter.convert(node) ?: return@mapNotNull null
            nextCache[node.startOffset] = CachedBlock(slice, block)
            block
        }
        cache = nextCache
        return MarkdownDocument(blocks = blocks, revealKeys = blocks.revealKeys(), inlineStyle = style)
    }

    private class CachedBlock(val source: String, val block: MarkdownBlock)
}

private class BlockConverter(
    private val source: String,
    private val links: LinkMap?,
    private val style: MarkdownInlineStyle,
) {
    fun convert(node: ASTNode): MarkdownBlock? = when (node.type) {
        MarkdownTokenTypes.EOL,
        MarkdownTokenTypes.WHITE_SPACE,
        MarkdownTokenTypes.BLOCK_QUOTE,
        MarkdownElementTypes.LINK_DEFINITION,
        -> null

        MarkdownElementTypes.PARAGRAPH -> paragraph(node)
        MarkdownElementTypes.ATX_1 -> heading(node, 1, MarkdownTokenTypes.ATX_CONTENT)
        MarkdownElementTypes.ATX_2 -> heading(node, 2, MarkdownTokenTypes.ATX_CONTENT)
        MarkdownElementTypes.ATX_3 -> heading(node, 3, MarkdownTokenTypes.ATX_CONTENT)
        MarkdownElementTypes.ATX_4 -> heading(node, 4, MarkdownTokenTypes.ATX_CONTENT)
        MarkdownElementTypes.ATX_5 -> heading(node, 5, MarkdownTokenTypes.ATX_CONTENT)
        MarkdownElementTypes.ATX_6 -> heading(node, 6, MarkdownTokenTypes.ATX_CONTENT)
        MarkdownElementTypes.SETEXT_1 -> heading(node, 1, MarkdownTokenTypes.SETEXT_CONTENT)
        MarkdownElementTypes.SETEXT_2 -> heading(node, 2, MarkdownTokenTypes.SETEXT_CONTENT)
        MarkdownElementTypes.CODE_FENCE -> codeFence(node)
        MarkdownElementTypes.CODE_BLOCK -> indentedCode(node)
        MarkdownElementTypes.BLOCK_QUOTE -> MarkdownQuote(node.startOffset, children(node))
        GFMElementTypes.ALERT -> alert(node)
        MarkdownElementTypes.UNORDERED_LIST -> list(node, ordered = false)
        MarkdownElementTypes.ORDERED_LIST -> list(node, ordered = true)
        GFMElementTypes.TABLE -> table(node)
        MarkdownTokenTypes.HORIZONTAL_RULE -> MarkdownRule(node.startOffset)
        // 未支持的块（HTML 块等）按原文显示：宁可露出标记，也不能让内容凭空消失。
        else -> raw(node)
    }

    private fun children(node: ASTNode): List<MarkdownBlock> = node.children.mapNotNull(::convert)

    private fun paragraph(node: ASTNode): MarkdownBlock? {
        val meaningful = node.children.filterNot { it.isWhitespaceLike() }
        val math = meaningful.singleOrNull()?.takeIf { it.type == GFMElementTypes.BLOCK_MATH }
        if (math != null) {
            return MarkdownCode(
                offset = node.startOffset,
                language = MATH_LANGUAGE,
                text = AnnotatedString(mathSource(math)),
                isMath = true,
            )
        }
        val text = inline(node.children)
        return if (text.isEmpty()) null else MarkdownParagraph(node.startOffset, text)
    }

    private fun heading(node: ASTNode, level: Int, contentType: org.intellij.markdown.IElementType): MarkdownBlock {
        val content = node.child(contentType)?.children.orEmpty()
        return MarkdownHeading(node.startOffset, level, inline(content))
    }

    /**
     * 围栏内容由 CODE_FENCE_CONTENT 与 EOL 交替组成；列表或引用里的缩进是独立的
     * WHITE_SPACE 节点，属于外层结构，拼接时跳过。
     */
    private fun codeFence(node: ASTNode): MarkdownBlock {
        val language = node.child(MarkdownTokenTypes.FENCE_LANG)
            ?.text()?.trim()?.substringBefore(' ')?.takeIf(String::isNotEmpty)
        val body = StringBuilder()
        var inBody = false
        for (child in node.children) {
            when (child.type) {
                MarkdownTokenTypes.CODE_FENCE_END -> break
                MarkdownTokenTypes.EOL -> if (inBody) body.append('\n') else inBody = true
                MarkdownTokenTypes.CODE_FENCE_CONTENT -> if (inBody) body.append(child.text())
            }
        }
        if (body.endsWith('\n')) body.setLength(body.length - 1)
        return MarkdownCode(node.startOffset, language, AnnotatedString(body.toString()))
    }

    private fun indentedCode(node: ASTNode): MarkdownBlock {
        val body = buildString {
            node.children.forEach { child ->
                when (child.type) {
                    MarkdownTokenTypes.CODE_LINE -> append(child.text().removeIndent(4))
                    MarkdownTokenTypes.EOL -> append('\n')
                }
            }
        }.trimEnd('\n')
        return MarkdownCode(node.startOffset, language = null, text = AnnotatedString(body))
    }

    private fun alert(node: ASTNode): MarkdownBlock {
        val title = node.child(GFMTokenTypes.ALERT_TITLE)?.text().orEmpty()
        val kind = when (title.removeSurrounding("[!", "]").uppercase()) {
            "TIP" -> MarkdownAlertKind.Tip
            "IMPORTANT" -> MarkdownAlertKind.Important
            "WARNING" -> MarkdownAlertKind.Warning
            "CAUTION" -> MarkdownAlertKind.Caution
            else -> MarkdownAlertKind.Note
        }
        val blocks = node.children.filterNot { it.type == GFMTokenTypes.ALERT_TITLE }.mapNotNull(::convert)
        return MarkdownAlert(node.startOffset, kind, blocks)
    }

    private fun list(node: ASTNode, ordered: Boolean): MarkdownBlock {
        val itemNodes = node.children.filter { it.type == MarkdownElementTypes.LIST_ITEM }
        val start = if (ordered) {
            itemNodes.firstOrNull()?.child(MarkdownTokenTypes.LIST_NUMBER)
                ?.text()?.takeWhile(Char::isDigit)?.toIntOrNull() ?: 1
        } else {
            null
        }
        val items = itemNodes.mapIndexed { index, item ->
            val blocks = item.children.mapNotNull { child ->
                when (child.type) {
                    MarkdownTokenTypes.LIST_BULLET,
                    MarkdownTokenTypes.LIST_NUMBER,
                    GFMTokenTypes.CHECK_BOX,
                    -> null
                    else -> convert(child)
                }
            }
            MarkdownListItem(
                offset = item.startOffset,
                number = start?.plus(index),
                checked = item.child(GFMTokenTypes.CHECK_BOX)?.text()?.let { box -> 'x' in box || 'X' in box },
                blocks = blocks,
                markerRevealKey = blocks.firstRevealKey().takeUnless { blocks.containsDocumentImage() },
                containsImage = blocks.containsDocumentImage(),
            )
        }
        val loose = node.hasBlankLine() || itemNodes.any { it.hasBlankLine() }
        return MarkdownList(node.startOffset, ordered, loose, items)
    }

    private fun table(node: ASTNode): MarkdownBlock? {
        val header = node.child(GFMElementTypes.HEADER)?.cells() ?: return null
        val columns = header.size
        val alignments = node.children
            .firstOrNull { it.type == GFMTokenTypes.TABLE_SEPARATOR && '-' in it.text() }
            ?.text()?.parseAlignments(columns)
            ?: List(columns) { MarkdownColumnAlign.Start }
        // GFM：多余的单元格丢弃，缺少的补空，所有行与表头列数一致。
        val rows = node.children.filter { it.type == GFMElementTypes.ROW }.map { row ->
            val cells = row.cells()
            List(columns) { column ->
                cells.getOrNull(column) ?: MarkdownTableCell(row.endOffset, AnnotatedString(""))
            }
        }
        return MarkdownTable(node.startOffset, alignments, header, rows)
    }

    private fun ASTNode.cells(): List<MarkdownTableCell> = children
        .filter { it.type == GFMTokenTypes.CELL }
        .map { cell -> MarkdownTableCell(cell.startOffset, inline(cell.children)) }

    private fun raw(node: ASTNode): MarkdownBlock? {
        val text = node.text().trim()
        return if (text.isEmpty()) null else MarkdownParagraph(node.startOffset, AnnotatedString(text))
    }

    private fun inline(nodes: List<ASTNode>): AnnotatedString = inlineText(nodes, source, links, style)

    private fun mathSource(node: ASTNode): String {
        val children = node.children
        if (children.size < 2) return node.text()
        return source.substring(children.first().endOffset, children.last().startOffset).trim('\n', ' ')
    }

    private fun ASTNode.text(): String = source.substring(startOffset, endOffset)

    /**
     * 两个连续 EOL 即源码中的空行；出现在列表项之间或列表项内部时，该列表为松散列表。
     * 末尾的空行属于列表之后的内容，不计入。
     */
    private fun ASTNode.hasBlankLine(): Boolean {
        val lastContent = children.indexOfLast { it.type != MarkdownTokenTypes.EOL }
        return (1..lastContent).any { index ->
            children[index - 1].type == MarkdownTokenTypes.EOL && children[index].type == MarkdownTokenTypes.EOL
        }
    }

    private fun ASTNode.isWhitespaceLike(): Boolean =
        type == MarkdownTokenTypes.WHITE_SPACE || type == MarkdownTokenTypes.EOL

    private companion object {
        const val MATH_LANGUAGE = "LaTeX"
    }
}

private fun String.parseAlignments(columns: Int): List<MarkdownColumnAlign> {
    val specs = trim().removePrefix("|").removeSuffix("|").split('|').map(String::trim)
    return List(columns) { index ->
        val spec = specs.getOrNull(index).orEmpty()
        when {
            spec.startsWith(':') && spec.endsWith(':') -> MarkdownColumnAlign.Center
            spec.endsWith(':') -> MarkdownColumnAlign.End
            else -> MarkdownColumnAlign.Start
        }
    }
}

private fun String.removeIndent(max: Int): String {
    var index = 0
    while (index < length && index < max && this[index] == ' ') index += 1
    return substring(index)
}

internal fun List<MarkdownBlock>.revealKeys(): Set<RevealBlockKey> = buildSet {
    fun collect(block: MarkdownBlock) {
        when (block) {
            is MarkdownTextBlock -> {
                val images = block.text.getStringAnnotations(DOCUMENT_IMAGE_TAG, 0, block.text.length)
                if (images.isEmpty()) {
                    if (block.text.isNotEmpty()) add(block.revealKey)
                } else {
                    // Image paragraphs already displayed selectable captions without reveal.
                    // Do not count placeholders/captions as pending text records.
                }
            }
            is MarkdownQuote -> block.blocks.forEach(::collect)
            is MarkdownAlert -> block.blocks.forEach(::collect)
            is MarkdownList -> block.items.forEach { item -> item.blocks.forEach(::collect) }
            is MarkdownTable -> (block.header + block.rows.flatten()).forEach(::collect)
            is MarkdownRule -> Unit
        }
    }
    this@revealKeys.forEach(::collect)
}

/** 列表项 marker 的显示时机：项内按源码顺序第一个有文字的块开始显现时。 */
private fun List<MarkdownBlock>.firstRevealKey(): RevealBlockKey? = asSequence()
    .mapNotNull { block ->
        when (block) {
            is MarkdownTextBlock -> listOf(block).revealKeys().minOrNull()
            is MarkdownQuote -> block.blocks.firstRevealKey()
            is MarkdownAlert -> block.blocks.firstRevealKey()
            is MarkdownList -> block.items.firstOrNull()?.markerRevealKey
            is MarkdownTable -> block.header.firstOrNull { it.text.isNotEmpty() }?.revealKey
            is MarkdownRule -> null
        }
    }
    .firstOrNull()

internal fun List<MarkdownBlock>.containsDocumentImage(): Boolean = any { block ->
    when (block) {
        is MarkdownTextBlock -> block.text.getStringAnnotations(DOCUMENT_IMAGE_TAG, 0, block.text.length).isNotEmpty()
        is MarkdownQuote -> block.blocks.containsDocumentImage()
        is MarkdownAlert -> block.blocks.containsDocumentImage()
        is MarkdownList -> block.items.any { it.containsImage }
        is MarkdownTable -> (block.header + block.rows.flatten()).containsDocumentImage()
        is MarkdownRule -> false
    }
}
