package com.linuxdroid.core.runtime

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * A styled text fragment within a terminal line.
 */
data class StyledSpan(
    val text: String,
    val color: Long = 0xFFE0E0E0,
    val isBold: Boolean = false,
    val isUnderline: Boolean = false,
    val isInverse: Boolean = false,
)

/**
 * A rendered terminal line containing styled text spans.
 */
data class TerminalLineData(
    val spans: List<StyledSpan>,
) {
    val rawText: String get() = spans.joinToString("") { it.text }

    companion object {
        fun simple(text: String, color: Long = 0xFFE0E0E0): TerminalLineData =
            TerminalLineData(listOf(StyledSpan(text, color)))
    }
}

private class TerminalChar(
    var char: Char,
    var color: Long,
    var isBold: Boolean,
    var isUnderline: Boolean,
)

/**
 * High-performance, thread-safe ANSI terminal screen buffer.
 *
 * Implements VT100 / ANSI escape sequence parsing:
 * - Color codes (30-37, 90-97, default 39)
 * - Text attributes (bold, underline, reset)
 * - Line editing & backspace (\b, \x7f, \x1b[K, \x1b[2K)
 * - Cursor navigation & line breaks (\r, \n)
 * - Clear screen (\x1b[2J, \x1b[H)
 */
class TerminalBuffer(
    private val maxScrollbackLines: Int = 2000,
) {
    private val _lines = MutableStateFlow<List<TerminalLineData>>(emptyList())
    val lines: StateFlow<List<TerminalLineData>> = _lines.asStateFlow()

    private val _activeCursorCol = MutableStateFlow(0)
    val activeCursorCol: StateFlow<Int> = _activeCursorCol.asStateFlow()

    private val linesList = ArrayList<ArrayList<TerminalChar>>()

    private var cursorCol = 0
    private var currentColor: Long = 0xFFE0E0E0
    private var currentBold = false
    private var currentUnderline = false

    private val ansiColors = longArrayOf(
        0xFF1E1E1E, // 0: Black
        0xFFE57373, // 1: Red
        0xFF81C784, // 2: Green
        0xFFFFD54F, // 3: Yellow
        0xFF64B5F6, // 4: Blue
        0xFFBA68C8, // 5: Magenta
        0xFF4DD0E1, // 6: Cyan
        0xFFE0E0E0, // 7: White
    )

    private val brightAnsiColors = longArrayOf(
        0xFF757575, // 0: Bright Black (Gray)
        0xFFFF8A80, // 1: Bright Red
        0xFFA5D6A7, // 2: Bright Green
        0xFFFFE082, // 3: Bright Yellow
        0xFF90CAF9, // 4: Bright Blue
        0xFFCE93D8, // 5: Bright Magenta
        0xFF80DEEA, // 6: Bright Cyan
        0xFFFFFFFF, // 7: Bright White
    )

    init {
        clear()
    }

    @Synchronized
    fun clear() {
        linesList.clear()
        linesList.add(ArrayList())
        cursorCol = 0
        _activeCursorCol.value = 0
        publishLines()
    }

    /**
     * Appends raw output data from the PTY stream and processes ANSI codes.
     */
    @Synchronized
    fun append(data: ByteArray, length: Int) {
        val text = String(data, 0, length, Charsets.UTF_8)
        var i = 0
        val len = text.length

        while (i < len) {
            val c = text[i]

            when {
                c == '\u001B' -> { // ESC
                    // Look for CSI sequence \x1b[
                    if (i + 1 < len && text[i + 1] == '[') {
                        var j = i + 2
                        while (j < len && (text[j] in '0'..'9' || text[j] == ';' || text[j] == '?')) {
                            j++
                        }
                        if (j < len) {
                            val cmd = text[j]
                            val params = text.substring(i + 2, j)
                            handleCsiSequence(cmd, params)
                            i = j + 1
                            continue
                        }
                    } else if (i + 1 < len && text[i + 1] == ']') {
                        // OSC sequence \x1b]...\x07
                        var j = i + 2
                        while (j < len && text[j] != '\u0007' && !(text[j] == '\u001B' && j + 1 < len && text[j + 1] == '\\')) {
                            j++
                        }
                        i = if (j < len) j + 1 else len
                        continue
                    }
                    i++
                }
                c == '\r' -> {
                    cursorCol = 0
                    i++
                }
                c == '\n' -> {
                    addNewLine()
                    cursorCol = 0
                    i++
                }
                c == '\b' || c == '\u007F' -> {
                    if (cursorCol > 0) {
                        cursorCol--
                    }
                    i++
                }
                c == '\t' -> {
                    val nextTab = ((cursorCol / 8) + 1) * 8
                    val spaces = nextTab - cursorCol
                    for (s in 0 until spaces) {
                        insertChar(' ')
                    }
                    i++
                }
                c == '\u0007' -> { // Bell
                    i++
                }
                else -> {
                    insertChar(c)
                    i++
                }
            }
        }

        publishLines()
    }

    private fun insertChar(c: Char) {
        val currentLine = getCurrentLine()
        val tChar = TerminalChar(c, currentColor, currentBold, currentUnderline)

        if (cursorCol < currentLine.size) {
            currentLine[cursorCol] = tChar
        } else {
            while (currentLine.size < cursorCol) {
                currentLine.add(TerminalChar(' ', 0xFFE0E0E0, isBold = false, isUnderline = false))
            }
            currentLine.add(tChar)
        }
        cursorCol++
    }

    private fun addNewLine() {
        if (linesList.size >= maxScrollbackLines) {
            linesList.removeAt(0)
        }
        linesList.add(ArrayList())
    }

    private fun getCurrentLine(): ArrayList<TerminalChar> {
        if (linesList.isEmpty()) {
            linesList.add(ArrayList())
        }
        return linesList.last()
    }

    private fun handleCsiSequence(cmd: Char, params: String) {
        when (cmd) {
            'm' -> { // SGR: Select Graphic Rendition
                val tokens = if (params.isEmpty()) listOf(0) else params.split(";").mapNotNull { it.toIntOrNull() }
                for (param in tokens) {
                    when (param) {
                        0 -> { // Reset
                            currentColor = 0xFFE0E0E0
                            currentBold = false
                            currentUnderline = false
                        }
                        1 -> currentBold = true
                        4 -> currentUnderline = true
                        in 30..37 -> currentColor = ansiColors[param - 30]
                        39 -> currentColor = 0xFFE0E0E0 // Default FG
                        in 90..97 -> currentColor = brightAnsiColors[param - 90]
                    }
                }
            }
            'J' -> { // Erase in Display
                when (params) {
                    "2", "3" -> clear()
                }
            }
            'K' -> { // Erase in Line
                val line = getCurrentLine()
                when (params) {
                    "", "0" -> { // Clear from cursor to end
                        while (line.size > cursorCol) {
                            line.removeAt(line.size - 1)
                        }
                    }
                    "1" -> { // Clear from start to cursor
                        for (idx in 0 until minOf(cursorCol, line.size)) {
                            line[idx] = TerminalChar(' ', 0xFFE0E0E0, isBold = false, isUnderline = false)
                        }
                    }
                    "2" -> { // Clear entire line
                        line.clear()
                        cursorCol = 0
                    }
                }
            }
            'H', 'f' -> { // Cursor Position
                if (params.isEmpty() || params == "1;1" || params == ";") {
                    cursorCol = 0
                }
            }
            'C' -> { // Cursor Forward \x1b[<n>C
                val n = params.toIntOrNull() ?: 1
                cursorCol += n
            }
            'D' -> { // Cursor Backward \x1b[<n>D
                val n = params.toIntOrNull() ?: 1
                cursorCol = maxOf(0, cursorCol - n)
            }
            'G' -> { // Cursor Character Absolute \x1b[<n>G
                val n = params.toIntOrNull() ?: 1
                cursorCol = maxOf(0, n - 1)
            }
            'P' -> { // Delete Character(s) \x1b[<n>P
                val n = params.toIntOrNull() ?: 1
                val line = getCurrentLine()
                repeat(n) {
                    if (cursorCol < line.size) {
                        line.removeAt(cursorCol)
                    }
                }
            }
        }
    }

    private fun publishLines() {
        val result = ArrayList<TerminalLineData>(linesList.size)
        for (lineChars in linesList) {
            if (lineChars.isEmpty()) {
                result.add(TerminalLineData(emptyList()))
                continue
            }

            val spans = ArrayList<StyledSpan>()
            var currentSpanText = StringBuilder()
            var spanColor = lineChars[0].color
            var spanBold = lineChars[0].isBold
            var spanUnderline = lineChars[0].isUnderline

            for (tc in lineChars) {
                if (tc.color != spanColor || tc.isBold != spanBold || tc.isUnderline != spanUnderline) {
                    if (currentSpanText.isNotEmpty()) {
                        spans.add(StyledSpan(currentSpanText.toString(), spanColor, spanBold, spanUnderline))
                        currentSpanText = StringBuilder()
                    }
                    spanColor = tc.color
                    spanBold = tc.isBold
                    spanUnderline = tc.isUnderline
                }
                currentSpanText.append(tc.char)
            }

            if (currentSpanText.isNotEmpty()) {
                spans.add(StyledSpan(currentSpanText.toString(), spanColor, spanBold, spanUnderline))
            }

            result.add(TerminalLineData(spans))
        }
        _lines.value = result
        _activeCursorCol.value = cursorCol
    }

    /**
     * Extracts the complete scrollback history as plain text (stripped of ANSI styling).
     */
    @Synchronized
    fun getPlainText(): String {
        return linesList.joinToString("\n") { lineChars ->
            lineChars.map { it.char }.joinToString("")
        }
    }

    /**
     * Extracts the most recent [count] lines of terminal text.
     */
    @Synchronized
    fun getRecentLines(count: Int = 100): List<String> {
        val lines = linesList.takeLast(count)
        return lines.map { lineChars ->
            lineChars.map { it.char }.joinToString("")
        }
    }
}
