package io.github.mangi.eta.ui.markdown

import org.intellij.markdown.IElementType
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.flavours.gfm.GFMElementTypes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamingGfmParserTest {
    @Test
    fun headingIsParsedAsHeadingFromFirstStreamingSnapshot() {
        val snapshot = StreamingGfmParserSession().parse(
            source = "## 标题",
            isComplete = false,
        )

        assertEquals("## 标题", snapshot.renderedSource)
        assertEquals(MarkdownElementTypes.ATX_2, snapshot.state.node.children.single().type)
    }

    @Test
    fun tableHeaderIsBufferedUntilDelimiterConfirmsTheBlock() {
        assertEquals(
            "",
            StreamingGfmProjection.project(
                source = "| 指标 | 数值 |",
                isComplete = false,
            ),
        )
        assertEquals(
            "",
            StreamingGfmProjection.project(
                source = "| 指标 | 数值 |\n| --",
                isComplete = false,
            ),
        )

        val confirmed = "| 指标 | 数值 |\n| --- | --- |"
        val snapshot = StreamingGfmParserSession().parse(
            source = confirmed,
            isComplete = false,
        )

        assertEquals(confirmed, snapshot.renderedSource)
        assertEquals(GFMElementTypes.TABLE, snapshot.state.node.children.single().type)
    }

    @Test
    fun ordinaryPipeTextIsReleasedWhenNextLineCannotBeATableDelimiter() {
        val source = "请选择 A | B\n这不是分隔行"

        assertEquals(
            source,
            StreamingGfmProjection.project(source = source, isComplete = false),
        )
    }

    @Test
    fun incompleteStrongDelimiterUsesVirtualEofClosure() {
        val snapshot = StreamingGfmParserSession().parse(
            source = "说明 **压力",
            isComplete = false,
        )

        assertEquals("说明 **压力**", snapshot.renderedSource)
        assertNotNull(snapshot.state.node.findRecursively(MarkdownElementTypes.STRONG))
    }

    @Test
    fun incompleteInlineCodeUsesVirtualEofClosure() {
        val snapshot = StreamingGfmParserSession().parse(
            source = "执行 `adb shell",
            isComplete = false,
        )

        assertEquals("执行 `adb shell`", snapshot.renderedSource)
        assertNotNull(snapshot.state.node.findRecursively(MarkdownElementTypes.CODE_SPAN))
    }

    @Test
    fun incompleteLinkIsNotPublishedAsRawMarkdown() {
        val snapshot = StreamingGfmParserSession().parse(
            source = "参考 [官方文档](https://example.com/do",
            isComplete = false,
        )

        assertEquals("参考 ", snapshot.renderedSource)
        assertFalse(snapshot.renderedSource.contains('['))
    }

    @Test
    fun openFenceRendersAsCodeWithoutChangingStoredSource() {
        val source = "```kotlin\nval answer = 42"
        val snapshot = StreamingGfmParserSession().parse(
            source = source,
            isComplete = false,
        )

        assertEquals(source, snapshot.originalSource)
        assertTrue(snapshot.renderedSource.endsWith("\n```"))
        assertNotNull(snapshot.state.node.findRecursively(MarkdownElementTypes.CODE_FENCE))
    }

    @Test
    fun completionParsesExactOriginalSourceWithoutVirtualCharacters() {
        val source = "未闭合 **标记"
        val snapshot = StreamingGfmParserSession().parse(
            source = source,
            isComplete = true,
        )

        assertEquals(source, snapshot.renderedSource)
        assertTrue(snapshot.isComplete)
    }

    private fun ASTNode.findRecursively(type: IElementType): ASTNode? {
        if (this.type == type) return this
        return children.firstNotNullOfOrNull { child -> child.findRecursively(type) }
    }
}
