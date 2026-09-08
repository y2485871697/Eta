package io.github.mangi.eta.agent.terminal

/**
 * 终端单元格样式的中性表示：颜色为 0xAARRGGBB 的 Long，不依赖 Compose。
 * 块式输出渲染（ui 层）与控制台屏幕缓冲区共用同一份 SGR 解释，避免两套调色板漂移。
 */
internal data class SgrStyle(
    val fg: Long? = null,
    val bg: Long? = null,
    val bold: Boolean = false,
    val dim: Boolean = false,
    val italic: Boolean = false,
    val underline: Boolean = false,
) {
    val isPlain: Boolean
        get() = fg == null && bg == null && !bold && !dim && !italic && !underline

    companion object {
        val PLAIN = SgrStyle()
    }
}

/** SGR 参数序列解释器；[params] 为 CSI 与结尾 m 之间的原文（空串等价于 0）。 */
internal object AnsiSgr {

    fun apply(params: String, style: SgrStyle): SgrStyle {
        // 分隔符同时兼容 ; 与 :（部分程序以冒号发 truecolor 子参数）。
        val tokens = if (params.isEmpty()) {
            listOf(0)
        } else {
            params.split(';', ':').map { it.toIntOrNull() ?: 0 }
        }
        var current = style
        var k = 0
        while (k < tokens.size) {
            when (val p = tokens[k]) {
                0 -> current = SgrStyle.PLAIN
                1 -> current = current.copy(bold = true)
                2 -> current = current.copy(dim = true)
                3 -> current = current.copy(italic = true)
                4 -> current = current.copy(underline = true)
                22 -> current = current.copy(bold = false, dim = false)
                23 -> current = current.copy(italic = false)
                24 -> current = current.copy(underline = false)
                in 30..37 -> current = current.copy(fg = STANDARD[p - 30])
                38 -> readExtendedColor(tokens, k)?.let { (color, consumed) ->
                    current = current.copy(fg = color)
                    k += consumed
                }
                39 -> current = current.copy(fg = null)
                in 40..47 -> current = current.copy(bg = STANDARD[p - 40])
                48 -> readExtendedColor(tokens, k)?.let { (color, consumed) ->
                    current = current.copy(bg = color)
                    k += consumed
                }
                49 -> current = current.copy(bg = null)
                in 90..97 -> current = current.copy(fg = BRIGHT[p - 90])
                in 100..107 -> current = current.copy(bg = BRIGHT[p - 100])
            }
            k++
        }
        return current
    }

    /** 解析 38/48 的扩展颜色参数，返回颜色与额外消费的 token 数；参数不足时返回 null。 */
    private fun readExtendedColor(tokens: List<Int>, index: Int): Pair<Long, Int>? =
        when (tokens.getOrNull(index + 1)) {
            5 -> tokens.getOrNull(index + 2)?.let { n -> color256(n)?.let { it to 2 } }
            2 -> {
                val r = tokens.getOrNull(index + 2) ?: return null
                val g = tokens.getOrNull(index + 3) ?: return null
                val b = tokens.getOrNull(index + 4) ?: return null
                (0xFF000000L or (r.coerceIn(0, 255).toLong() shl 16) or
                    (g.coerceIn(0, 255).toLong() shl 8) or b.coerceIn(0, 255).toLong()) to 4
            }
            else -> null
        }

    fun color256(n: Int): Long? = when (n) {
        in 0..7 -> STANDARD[n]
        in 8..15 -> BRIGHT[n - 8]
        in 16..231 -> {
            val v = n - 16
            0xFF000000L or
                (CUBE_LEVELS[v / 36].toLong() shl 16) or
                (CUBE_LEVELS[(v / 6) % 6].toLong() shl 8) or
                CUBE_LEVELS[v % 6].toLong()
        }
        in 232..255 -> {
            val level = (8 + 10 * (n - 232)).toLong()
            0xFF000000L or (level shl 16) or (level shl 8) or level
        }
        else -> null
    }

    private val CUBE_LEVELS = intArrayOf(0, 95, 135, 175, 215, 255)

    val STANDARD: List<Long> = listOf(
        0xFF000000, 0xFFCD0000, 0xFF00CD00, 0xFFCDCD00,
        0xFF0000EE, 0xFFCD00CD, 0xFF00CDCD, 0xFFE5E5E5,
    )

    val BRIGHT: List<Long> = listOf(
        0xFF7F7F7F, 0xFFFF0000, 0xFF00FF00, 0xFFFFFF00,
        0xFF5C5CFF, 0xFFFF00FF, 0xFF00FFFF, 0xFFFFFFFF,
    )
}
