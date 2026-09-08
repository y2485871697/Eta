package io.github.mangi.eta.agent.terminal

/**
 * 控制台屏幕缓冲区：把 PTY 字节流解析成固定行列的字符网格 + 滚动历史。
 *
 * 只实现 TUI 实际使用的 VT 子集：SGR、光标移动（CUP/CUU/CUD/CUF/CUB/CHA/VPA）、
 * 行/屏擦除（ED/EL）、滚动区（DECSTBM）、插入/删除行与字符（IL/DL/ICH/DCH）、
 * 备用屏幕（1049/1047/1048）、光标显隐（?25）。不识别的序列丢弃。
 * 宽字符（CJK/emoji）占两格，右边界自动换行采用 deferred wrap。
 */
internal class TerminalScreenBuffer(
    val cols: Int,
    val rows: Int,
    private val maxScrollback: Int = 500,
) {
    init {
        require(cols > 0 && rows > 0) { "cols/rows 必须为正" }
    }

    data class Cell(
        val text: String = " ",
        val style: SgrStyle = SgrStyle.PLAIN,
        /** 宽字符的第二个占位格。 */
        val continuation: Boolean = false,
    )

    /** 一行网格；对象身份稳定，渲染层按 id + version 复用组合。 */
    class Line internal constructor(val id: Long, width: Int) {
        internal var cells = Array(width) { Cell() }
        var version = 0L
            internal set

        internal fun set(index: Int, cell: Cell) {
            cells[index] = cell
            version++
        }

        internal fun fill(cell: Cell) {
            cells.fill(cell)
            version++
        }

        fun snapshot(): List<Cell> = cells.toList()
    }

    private var nextLineId = 0L
    private var screen = Array(rows) { Line(nextLineId++, cols) }
    private val scrollback = ArrayDeque<Line>()

    /** 每次 process 产生变化时递增；渲染层据此拉取新快照。 */
    var version = 0L
        private set

    var cursorRow = 0
        private set
    var cursorCol = 0
        private set
    var cursorVisible = true
        private set

    private var style = SgrStyle.PLAIN
    private var scrollTop = 0
    private var scrollBottom = rows - 1
    private var wrapPending = false
    private var savedCursorRow = 0
    private var savedCursorCol = 0
    private var savedStyle = SgrStyle.PLAIN
    private var altActive = false
    private var mainScreen: Array<Line>? = null
    private var mainCursorRow = 0
    private var mainCursorCol = 0

    /** 滚动历史 + 当前屏幕；Line 身份跨快照稳定。 */
    fun lines(): List<Line> = scrollback.toList() + screen.toList()

    /** 测试与诊断用：当前屏幕的纯文本行（尾随空格裁剪）。 */
    fun dump(): List<String> = screen.map { line ->
        line.cells.joinToString("") { it.text }.trimEnd()
    }

    /** 当前屏幕第 [row] 行（不含滚动历史）。 */
    fun screenLine(row: Int): Line = screen[row]

    fun process(text: String) {
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                c.code == 0x1B -> i = consumeEscape(text, i)
                c == '\n' || c.code == 0x0B || c.code == 0x0C -> {
                    linefeed()
                    wrapPending = false
                    i++
                }
                c == '\r' -> {
                    cursorCol = 0
                    wrapPending = false
                    i++
                }
                c == '\b' -> {
                    if (cursorCol > 0) cursorCol--
                    wrapPending = false
                    i++
                }
                c == '\t' -> {
                    cursorCol = ((cursorCol / 8) + 1) * 8
                    if (cursorCol >= cols) cursorCol = cols - 1
                    wrapPending = false
                    i++
                }
                c.code < 0x20 || c.code == 0x7F -> i++
                else -> {
                    val cp = if (c.isHighSurrogate() && i + 1 < text.length && text[i + 1].isLowSurrogate()) {
                        charArrayOf(c, text[i + 1]).concatToString().codePointAt(0)
                    } else {
                        c.code
                    }
                    val width = terminalCharWidth(cp)
                    if (width > 0) {
                        putChar(String(Character.toChars(cp)), width)
                    }
                    i += if (cp > 0xFFFF) 2 else 1
                }
            }
        }
        version++
    }

    private fun putChar(value: String, width: Int) {
        if (wrapPending) {
            cursorCol = 0
            linefeed()
            wrapPending = false
        }
        if (width == 2 && cursorCol == cols - 1) {
            // 宽字符不压右边界，换到下一行书写
            cursorCol = 0
            linefeed()
        }
        val line = screen[cursorRow]
        line.set(cursorCol, Cell(value, style))
        if (width == 2) {
            line.set(cursorCol + 1, Cell("", style, continuation = true))
        }
        if (cursorCol + width >= cols) {
            cursorCol = cols - 1
            wrapPending = true
        } else {
            cursorCol += width
        }
    }

    private fun linefeed() {
        if (cursorRow == scrollBottom) {
            scrollUp(1)
        } else if (cursorRow < rows - 1) {
            cursorRow++
        }
    }

    private fun scrollUp(count: Int) {
        repeat(count) {
            val regionHeight = scrollBottom - scrollTop + 1
            if (regionHeight <= 0) return
            val top = screen[scrollTop]
            for (row in scrollTop until scrollBottom) {
                screen[row] = screen[row + 1]
            }
            screen[scrollBottom] = top
            if (scrollTop == 0 && !altActive) {
                scrollback.addLast(top)
                while (scrollback.size > maxScrollback) scrollback.removeFirst()
                screen[scrollBottom] = Line(nextLineId++, cols)
            } else {
                top.fill(eraseCell())
            }
        }
    }

    private fun scrollDown(count: Int) {
        repeat(count) {
            val regionHeight = scrollBottom - scrollTop + 1
            if (regionHeight <= 0) return
            val bottom = screen[scrollBottom]
            for (row in scrollBottom downTo scrollTop + 1) {
                screen[row] = screen[row - 1]
            }
            bottom.fill(eraseCell())
            screen[scrollTop] = bottom
        }
    }

    /** 擦除按背景色继承（bce）：擦出的空白保留当前 SGR 背景。 */
    private fun eraseCell(): Cell =
        if (style.bg != null) Cell(" ", SgrStyle(bg = style.bg)) else Cell()

    private fun consumeEscape(text: String, start: Int): Int {
        if (start + 1 >= text.length) return text.length
        return when (text[start + 1]) {
            '[' -> consumeCsi(text, start)
            ']' -> {
                var j = start + 2
                while (j < text.length) {
                    if (text[j].code == 0x07) {
                        j++
                        break
                    }
                    if (text[j].code == 0x1B && j + 1 < text.length && text[j + 1] == '\\') {
                        j += 2
                        break
                    }
                    j++
                }
                j
            }
            '7' -> {
                savedCursorRow = cursorRow
                savedCursorCol = cursorCol
                savedStyle = style
                start + 2
            }
            '8' -> {
                cursorRow = savedCursorRow.coerceIn(0, rows - 1)
                cursorCol = savedCursorCol.coerceIn(0, cols - 1)
                style = savedStyle
                wrapPending = false
                start + 2
            }
            'M' -> {
                if (cursorRow == scrollTop) scrollDown(1) else if (cursorRow > 0) cursorRow--
                start + 2
            }
            'c' -> {
                reset()
                start + 2
            }
            '(', ')', '#', '%' -> (start + 3).coerceAtMost(text.length)
            else -> start + 2
        }
    }

    private fun consumeCsi(text: String, start: Int): Int {
        var i = start + 2
        var private = false
        if (i < text.length && text[i] == '?') {
            private = true
            i++
        }
        val paramsStart = i
        while (i < text.length && (text[i].isDigit() || text[i] == ';' || text[i] == ':')) i++
        val params = text.substring(paramsStart, i)
        while (i < text.length && text[i] in ' '..'/') i++
        if (i >= text.length) return text.length
        dispatchCsi(private, params, text[i])
        return i + 1
    }

    private fun dispatchCsi(private: Boolean, params: String, final: Char) {
        fun ints(default: Int): List<Int> =
            if (params.isEmpty()) listOf(default) else params.split(';', ':').map { it.toIntOrNull() ?: 0 }

        if (private) {
            when (params.toIntOrNull()) {
                25 -> cursorVisible = final == 'h'
                1048 -> if (final == 'h') saveCursor() else restoreCursor()
                1047 -> if (final == 'h') enterAltScreen() else exitAltScreen()
                1049 -> if (final == 'h') {
                    saveCursor()
                    enterAltScreen()
                } else {
                    exitAltScreen()
                    restoreCursor()
                }
            }
            return
        }
        when (final) {
            'm' -> style = AnsiSgr.apply(params, style)
            'A' -> cursorRow = (cursorRow - ints(1)[0]).coerceAtLeast(scrollTop)
            'B' -> cursorRow = (cursorRow + ints(1)[0]).coerceAtMost(scrollBottom)
            'C' -> cursorCol = (cursorCol + ints(1)[0]).coerceAtMost(cols - 1)
            'D' -> cursorCol = (cursorCol - ints(1)[0]).coerceAtLeast(0)
            'E' -> {
                cursorRow = (cursorRow + ints(1)[0]).coerceAtMost(scrollBottom)
                cursorCol = 0
            }
            'F' -> {
                cursorRow = (cursorRow - ints(1)[0]).coerceAtLeast(scrollTop)
                cursorCol = 0
            }
            'G', '`' -> cursorCol = (ints(1)[0] - 1).coerceIn(0, cols - 1)
            'd' -> cursorRow = (ints(1)[0] - 1).coerceIn(scrollTop, scrollBottom)
            'H', 'f' -> {
                val pair = ints(1)
                cursorRow = ((pair.getOrNull(0) ?: 1) - 1).coerceIn(0, rows - 1)
                cursorCol = ((pair.getOrNull(1) ?: 1) - 1).coerceIn(0, cols - 1)
            }
            'J' -> eraseInDisplay(ints(0)[0])
            'K' -> eraseInLine(ints(0)[0])
            'L' -> insertLines(ints(1)[0])
            'M' -> deleteLines(ints(1)[0])
            'P' -> deleteChars(ints(1)[0])
            '@' -> insertChars(ints(1)[0])
            'X' -> eraseChars(ints(1)[0])
            'S' -> scrollUp(ints(1)[0])
            'T' -> scrollDown(ints(1)[0])
            'r' -> {
                val pair = ints(1)
                val top = ((pair.getOrNull(0) ?: 1) - 1).coerceIn(0, rows - 1)
                val bottom = ((pair.getOrNull(1) ?: rows) - 1).coerceIn(0, rows - 1)
                if (top < bottom) {
                    scrollTop = top
                    scrollBottom = bottom
                }
                cursorRow = 0
                cursorCol = 0
            }
            's' -> saveCursor()
            'u' -> restoreCursor()
        }
        if (final != 'm') wrapPending = false
    }

    private fun saveCursor() {
        savedCursorRow = cursorRow
        savedCursorCol = cursorCol
        savedStyle = style
    }

    private fun restoreCursor() {
        cursorRow = savedCursorRow.coerceIn(0, rows - 1)
        cursorCol = savedCursorCol.coerceIn(0, cols - 1)
        style = savedStyle
    }

    private fun enterAltScreen() {
        if (altActive) return
        altActive = true
        mainScreen = screen
        mainCursorRow = cursorRow
        mainCursorCol = cursorCol
        screen = Array(rows) { Line(nextLineId++, cols) }
        cursorRow = 0
        cursorCol = 0
        scrollTop = 0
        scrollBottom = rows - 1
    }

    private fun exitAltScreen() {
        if (!altActive) return
        altActive = false
        mainScreen?.let { screen = it }
        mainScreen = null
        cursorRow = mainCursorRow
        cursorCol = mainCursorCol
        scrollTop = 0
        scrollBottom = rows - 1
    }

    private fun eraseInDisplay(mode: Int) {
        when (mode) {
            0 -> {
                eraseInLine(0)
                for (row in cursorRow + 1..rows - 1) screen[row].fill(eraseCell())
            }
            1 -> {
                for (row in 0 until cursorRow) screen[row].fill(eraseCell())
                eraseInLine(1)
            }
            2 -> for (row in 0 until rows) screen[row].fill(eraseCell())
            3 -> scrollback.clear()
        }
    }

    private fun eraseInLine(mode: Int) {
        val line = screen[cursorRow]
        val blank = eraseCell()
        when (mode) {
            0 -> for (col in cursorCol until cols) line.set(col, blank)
            1 -> for (col in 0..cursorCol) line.set(col, blank)
            2 -> line.fill(blank)
        }
    }

    private fun insertLines(count: Int) {
        if (cursorRow !in scrollTop..scrollBottom) return
        repeat(count.coerceAtMost(scrollBottom - cursorRow + 1)) {
            val bottom = screen[scrollBottom]
            for (row in scrollBottom downTo cursorRow + 1) {
                screen[row] = screen[row - 1]
            }
            bottom.fill(eraseCell())
            screen[cursorRow] = bottom
        }
    }

    private fun deleteLines(count: Int) {
        if (cursorRow !in scrollTop..scrollBottom) return
        repeat(count.coerceAtMost(scrollBottom - cursorRow + 1)) {
            val top = screen[cursorRow]
            for (row in cursorRow until scrollBottom) {
                screen[row] = screen[row + 1]
            }
            top.fill(eraseCell())
            screen[scrollBottom] = top
        }
    }

    private fun insertChars(count: Int) {
        val line = screen[cursorRow]
        val blank = eraseCell()
        repeat(count.coerceAtMost(cols - cursorCol)) {
            for (col in cols - 1 downTo cursorCol + 1) {
                line.set(col, line.cells[col - 1])
            }
            line.set(cursorCol, blank)
        }
    }

    private fun deleteChars(count: Int) {
        val line = screen[cursorRow]
        val blank = eraseCell()
        repeat(count.coerceAtMost(cols - cursorCol)) {
            for (col in cursorCol until cols - 1) {
                line.set(col, line.cells[col + 1])
            }
            line.set(cols - 1, blank)
        }
    }

    private fun eraseChars(count: Int) {
        val line = screen[cursorRow]
        val blank = eraseCell()
        val end = (cursorCol + count).coerceAtMost(cols)
        for (col in cursorCol until end) line.set(col, blank)
    }

    private fun reset() {
        screen = Array(rows) { Line(nextLineId++, cols) }
        scrollback.clear()
        cursorRow = 0
        cursorCol = 0
        style = SgrStyle.PLAIN
        scrollTop = 0
        scrollBottom = rows - 1
        wrapPending = false
        cursorVisible = true
        altActive = false
        mainScreen = null
    }
}

/** 终端字符宽度：CJK 全角/宽字符与 emoji 占两格，控制字符不占格。 */
internal fun terminalCharWidth(codePoint: Int): Int = when {
    codePoint < 0x20 || codePoint in 0x7F..0x9F -> 0
    codePoint in 0x1100..0x115F || // Hangul Jamo
        codePoint in 0x2E80..0x303E || // CJK Radicals .. CJK Symbols
        codePoint in 0x3041..0x33FF || // Hiragana .. CJK Compatibility
        codePoint in 0x3400..0x4DBF || // CJK Ext A
        codePoint in 0x4E00..0x9FFF || // CJK Unified
        codePoint in 0xA000..0xA4CF ||
        codePoint in 0xAC00..0xD7A3 || // Hangul Syllables
        codePoint in 0xF900..0xFAFF || // CJK Compatibility Ideographs
        codePoint in 0xFE30..0xFE4F ||
        codePoint in 0xFF00..0xFF60 || // Fullwidth Forms
        codePoint in 0xFFE0..0xFFE6 ||
        codePoint in 0x1F300..0x1F64F || // Emoji
        codePoint in 0x1F900..0x1F9FF ||
        codePoint in 0x20000..0x2FFFD ||
        codePoint in 0x30000..0x3FFFD -> 2
    else -> 1
}
