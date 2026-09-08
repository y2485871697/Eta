package io.github.mangi.eta.agent.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalScreenBufferTest {

    @Test
    fun plainTextWritesFromHomeAndTracksCursor() {
        val buffer = TerminalScreenBuffer(cols = 10, rows = 3)
        buffer.process("hello")
        assertEquals("hello", buffer.dump()[0])
        assertEquals(0, buffer.cursorRow)
        assertEquals(5, buffer.cursorCol)
    }

    @Test
    fun deferredWrapMovesToNextLineOnOverflow() {
        val buffer = TerminalScreenBuffer(cols = 5, rows = 3)
        buffer.process("abcde")
        assertEquals(0, buffer.cursorRow)
        assertEquals(4, buffer.cursorCol)
        buffer.process("f")
        assertEquals(listOf("abcde", "f", ""), buffer.dump())
        assertEquals(1, buffer.cursorRow)
        assertEquals(1, buffer.cursorCol)
    }

    @Test
    fun linefeedAtBottomScrollsIntoScrollback() {
        val buffer = TerminalScreenBuffer(cols = 10, rows = 3)
        buffer.process("one\r\ntwo\r\nthree\r\nfour\r\nfive")
        assertEquals(listOf("three", "four", "five"), buffer.dump())
        val all = buffer.lines()
        assertEquals(5, all.size)
        assertEquals("one", all[0].snapshot().joinToString("") { it.text }.trimEnd())
        assertEquals("two", all[1].snapshot().joinToString("") { it.text }.trimEnd())
    }

    @Test
    fun carriageReturnAndLinefeedAreIndependent() {
        val buffer = TerminalScreenBuffer(cols = 10, rows = 3)
        buffer.process("ab\rcd")
        assertEquals("cd", buffer.dump()[0])
    }

    @Test
    fun cupPositionsCursor() {
        val buffer = TerminalScreenBuffer(cols = 10, rows = 5)
        buffer.process("\u001B[2;3HX")
        assertEquals("", buffer.dump()[0])
        assertEquals("  X", buffer.dump()[1])
    }

    @Test
    fun eraseInLineAndDisplay() {
        val buffer = TerminalScreenBuffer(cols = 10, rows = 3)
        buffer.process("aaaaaaaaaabbbbbbbbbbcccccccccc")
        buffer.process("\u001B[1;5H\u001B[K")
        assertEquals("aaaa", buffer.dump()[0])
        buffer.process("\u001B[2J")
        assertEquals(listOf("", "", ""), buffer.dump())
    }

    @Test
    fun sgrColorsAttachToCellsAndReset() {
        val buffer = TerminalScreenBuffer(cols = 10, rows = 3)
        buffer.process("\u001B[31mred\u001B[0mplain")
        val line = buffer.screenLine(0).snapshot()
        assertEquals(AnsiSgr.STANDARD[1], line[0].style.fg)
        assertEquals(AnsiSgr.STANDARD[1], line[2].style.fg)
        assertEquals(SgrStyle.PLAIN, line[3].style)
    }

    @Test
    fun eraseInheritsBackgroundColor() {
        val buffer = TerminalScreenBuffer(cols = 10, rows = 3)
        buffer.process("\u001B[41m\u001B[2J")
        val line = buffer.screenLine(0).snapshot()
        assertEquals(AnsiSgr.STANDARD[1], line[0].style.bg)
    }

    @Test
    fun altScreenSwitchesAndRestores() {
        val buffer = TerminalScreenBuffer(cols = 10, rows = 3)
        buffer.process("main")
        buffer.process("\u001B[?1049h")
        assertEquals(listOf("", "", ""), buffer.dump())
        buffer.process("alt")
        assertEquals("alt", buffer.dump()[0])
        buffer.process("\u001B[?1049l")
        assertEquals("main", buffer.dump()[0])
    }

    @Test
    fun scrollRegionScrollsOnlyInsideRegion() {
        val buffer = TerminalScreenBuffer(cols = 10, rows = 4)
        buffer.process("\u001B[2;3r")
        buffer.process("\u001B[1;1Hheader")
        buffer.process("\u001B[2;1Ha\r\nb\r\nc\r\nd")
        val dump = buffer.dump()
        assertEquals("header", dump[0])
        assertEquals("c", dump[1])
        assertEquals("d", dump[2])
    }

    @Test
    fun wideCharOccupiesTwoCells() {
        val buffer = TerminalScreenBuffer(cols = 10, rows = 3)
        buffer.process("中x")
        val line = buffer.screenLine(0).snapshot()
        assertEquals("中", line[0].text)
        assertTrue(line[1].continuation)
        assertEquals("x", line[2].text)
        assertEquals(3, buffer.cursorCol)
    }

    @Test
    fun cursorVisibilityToggles() {
        val buffer = TerminalScreenBuffer(cols = 10, rows = 3)
        buffer.process("\u001B[?25l")
        assertFalse(buffer.cursorVisible)
        buffer.process("\u001B[?25h")
        assertTrue(buffer.cursorVisible)
    }

    @Test
    fun deleteAndInsertCharsShiftLineContent() {
        val buffer = TerminalScreenBuffer(cols = 10, rows = 3)
        buffer.process("abcdef")
        buffer.process("\u001B[1;2H\u001B[2P")
        assertEquals("adef", buffer.dump()[0])
        buffer.process("\u001B[1;1H\u001B[2@")
        assertEquals("  adef", buffer.dump()[0])
    }

    @Test
    fun versionBumpsOnProcess() {
        val buffer = TerminalScreenBuffer(cols = 10, rows = 3)
        val before = buffer.version
        buffer.process("x")
        assertTrue(buffer.version > before)
    }
}
