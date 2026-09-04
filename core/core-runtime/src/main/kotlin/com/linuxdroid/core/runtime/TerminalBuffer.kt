package com.linuxdroid.core.runtime

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CharsetDecoder
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/**
 * A styled text fragment within a terminal line.
 */
data class StyledSpan(
    val text: String,
    val color: Long = 0xFFE0E0E0,
    val isBold: Boolean = false,
    val isUnderline: Boolean = false,
    val isInverse: Boolean = false,
    val backgroundColor: Long = 0x00000000,
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
    var char: Char = ' ',
    var fgColor: Long = 0xFFE0E0E0L,
    var bgColor: Long = 0x00000000L,
    var isBold: Boolean = false,
    var isUnderline: Boolean = false,
    var isInverse: Boolean = false,
    var isDim: Boolean = false,
) {
    val effectiveFg: Long
        get() = if (isInverse) (if (bgColor != 0L) bgColor else 0xFF1E1E1EL) else fgColor

    val effectiveBg: Long
        get() = if (isInverse) fgColor else bgColor

    fun copy(): TerminalChar = TerminalChar(char, fgColor, bgColor, isBold, isUnderline, isInverse, isDim)
}

/**
 * Production-ready, thread-safe ANSI VT100 / xterm terminal screen buffer.
 *
 * Capabilities:
 * - Stateful streaming UTF-8 decoding (no split multi-byte character corruption)
 * - 2D cursor addressing matrix & screen grid (CUP, CUU, CUD, CUF, CUB, CHA, VPA)
 * - Alternate screen buffer switching (\x1b[?1049h / \x1b[?1049l) for curses/TUI apps (vim, nano, htop, less, tmux)
 * - Extended 256-color palette (\x1b[38;5;<n>m) & 24-bit TrueColor (\x1b[38;2;<r>;<g>;<b>m)
 * - Background color styling (\x1b[40..47m, \x1b[100..107m, \x1b[48;5;<n>m, \x1b[49m)
 * - Top and bottom scroll margins (DECSTBM \x1b[<top>;<bottom>r)
 * - Line & character editing (ED, EL, IL, DL, DCH, ICH)
 * - Save & restore cursor (DECSC / DECRC \x1b7 / \x1b8, ANSI \x1b[s / \x1b[u)
 */
class TerminalBuffer(
    private val maxScrollbackLines: Int = 2000,
) {
    private val _lines = MutableStateFlow<List<TerminalLineData>>(emptyList())
    val lines: StateFlow<List<TerminalLineData>> = _lines.asStateFlow()

    private val _activeCursorRow = MutableStateFlow(0)
    val activeCursorRow: StateFlow<Int> = _activeCursorRow.asStateFlow()

    private val _activeCursorCol = MutableStateFlow(0)
    val activeCursorCol: StateFlow<Int> = _activeCursorCol.asStateFlow()

    private val _isCursorVisible = MutableStateFlow(true)
    val isCursorVisible: StateFlow<Boolean> = _isCursorVisible.asStateFlow()

    var rows: Int = 24
        private set
    var cols: Int = 80
        private set

    // Primary screen scrollback history & active 2D grid
    private val scrollbackLines = ArrayList<ArrayList<TerminalChar>>()
    private val primaryScreen = ArrayList<ArrayList<TerminalChar>>()

    // Alternate screen 2D grid (used by full-screen TUI apps)
    private val alternateScreen = ArrayList<ArrayList<TerminalChar>>()
    var isAlternateScreen: Boolean = false
        private set

    // Cursor position within active screen (0-indexed)
    private var cursorRow = 0
    private var cursorCol = 0

    // Saved cursor state (DECSC / ANSI save cursor)
    private var savedCursorRow = 0
    private var savedCursorCol = 0
    private var savedColor = 0xFFE0E0E0L
    private var savedBgColor = 0x00000000L
    private var savedBold = false
    private var savedUnderline = false
    private var savedInverse = false
    private var savedDim = false

    // Scroll margins (0-indexed)
    private var scrollTop = 0
    private var scrollBottom = rows - 1

    // Current rendition attributes
    private var currentColor: Long = 0xFFE0E0E0L
    private var currentBgColor: Long = 0x00000000L
    private var currentBold = false
    private var currentUnderline = false
    private var currentInverse = false
    private var currentDim = false

    // Stateful UTF-8 stream decoder
    private val utf8Decoder: CharsetDecoder = StandardCharsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPLACE)
        .onUnmappableCharacter(CodingErrorAction.REPLACE)
    private var pendingBytes: ByteArray? = null

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
        initGrids()
        clear()
    }

    private fun initGrids() {
        primaryScreen.clear()
        alternateScreen.clear()
        for (r in 0 until rows) {
            primaryScreen.add(ArrayList())
            alternateScreen.add(ArrayList())
        }
        scrollTop = 0
        scrollBottom = rows - 1
    }

    private val activeGrid: ArrayList<ArrayList<TerminalChar>>
        get() = if (isAlternateScreen) alternateScreen else primaryScreen

    @Synchronized
    fun resize(newRows: Int, newCols: Int) {
        val r = maxOf(1, newRows)
        val c = maxOf(1, newCols)
        rows = r
        cols = c
        scrollTop = 0
        scrollBottom = r - 1

        while (primaryScreen.size < r) primaryScreen.add(ArrayList())
        while (alternateScreen.size < r) alternateScreen.add(ArrayList())

        cursorRow = cursorRow.coerceIn(0, r - 1)
        cursorCol = cursorCol.coerceIn(0, c - 1)
        publishLines()
    }

    @Synchronized
    fun clear() {
        scrollbackLines.clear()
        initGrids()
        cursorRow = 0
        cursorCol = 0
        isAlternateScreen = false
        _activeCursorRow.value = 0
        _activeCursorCol.value = 0
        publishLines()
    }

    /**
     * Decodes incoming byte chunk preserving incomplete multi-byte UTF-8 sequences.
     */
    private fun decodeStreamBytes(data: ByteArray, length: Int): String {
        val totalBytes: ByteArray = if (pendingBytes != null && pendingBytes!!.isNotEmpty()) {
            val combined = ByteArray(pendingBytes!!.size + length)
            System.arraycopy(pendingBytes!!, 0, combined, 0, pendingBytes!!.size)
            System.arraycopy(data, 0, combined, pendingBytes!!.size, length)
            pendingBytes = null
            combined
        } else {
            if (length == data.size) data else data.copyOf(length)
        }

        val inBuf = ByteBuffer.wrap(totalBytes)
        val outBuf = CharBuffer.allocate(totalBytes.size + 8)

        utf8Decoder.decode(inBuf, outBuf, false)

        if (inBuf.hasRemaining()) {
            val rem = inBuf.remaining()
            if (rem in 1..4) {
                val leftover = ByteArray(rem)
                inBuf.get(leftover)
                pendingBytes = leftover
            } else {
                utf8Decoder.reset()
                pendingBytes = null
            }
        } else {
            pendingBytes = null
        }

        outBuf.flip()
        return outBuf.toString()
    }

    /**
     * Appends raw output data from the PTY stream and processes ANSI codes.
     */
    @Synchronized
    fun append(data: ByteArray, length: Int) {
        val text = decodeStreamBytes(data, length)
        var i = 0
        val len = text.length

        while (i < len) {
            val c = text[i]

            when {
                c == '\u001B' -> { // ESC
                    if (i + 1 < len && text[i + 1] == '[') {
                        // CSI sequence: \x1b[ ... <cmd>
                        var j = i + 2
                        while (j < len && (text[j] in '0'..'9' || text[j] == ';' || text[j] == '?' || text[j] == ' ' || text[j] == '>')) {
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
                        // OSC sequence: \x1b] ... \x07 or \x1b\\
                        var j = i + 2
                        while (j < len && text[j] != '\u0007' && !(text[j] == '\u001B' && j + 1 < len && text[j + 1] == '\\')) {
                            j++
                        }
                        i = if (j < len) {
                            if (text[j] == '\u0007') j + 1 else j + 2
                        } else len
                        continue
                    } else if (i + 1 < len && text[i + 1] == '7') {
                        // DECSC: Save Cursor
                        saveCursor()
                        i += 2
                        continue
                    } else if (i + 1 < len && text[i + 1] == '8') {
                        // DECRC: Restore Cursor
                        restoreCursor()
                        i += 2
                        continue
                    } else if (i + 1 < len && text[i + 1] == 'M') {
                        // RI: Reverse Index (scroll down or move cursor up)
                        reverseIndex()
                        i += 2
                        continue
                    }
                    i++
                }
                c == '\r' -> {
                    cursorCol = 0
                    i++
                }
                c == '\n' -> {
                    lineFeed()
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
                    val spaces = minOf(cols - 1, nextTab) - cursorCol
                    for (s in 0 until maxOf(1, spaces)) {
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
        val grid = activeGrid
        ensureRowCapacity(grid, cursorRow)
        val line = grid[cursorRow]
        val tChar = TerminalChar(
            char = c,
            fgColor = currentColor,
            bgColor = currentBgColor,
            isBold = currentBold,
            isUnderline = currentUnderline,
            isInverse = currentInverse,
            isDim = currentDim
        )

        if (cursorCol < line.size) {
            line[cursorCol] = tChar
        } else {
            while (line.size < cursorCol) {
                line.add(TerminalChar(' ', 0xFFE0E0E0L, 0x00000000L))
            }
            line.add(tChar)
        }
        cursorCol++
        if (cursorCol >= cols) {
            cursorCol = cols - 1
        }
    }

    private fun ensureRowCapacity(grid: ArrayList<ArrayList<TerminalChar>>, targetRow: Int) {
        while (grid.size <= targetRow) {
            grid.add(ArrayList())
        }
    }

    private fun lineFeed() {
        if (cursorRow >= scrollBottom) {
            scrollUp()
            cursorRow = scrollBottom
        } else {
            cursorRow++
            if (cursorRow >= rows) {
                cursorRow = rows - 1
            }
        }
    }

    private fun scrollUp() {
        val grid = activeGrid
        if (scrollTop == 0 && !isAlternateScreen) {
            if (grid.isNotEmpty()) {
                val topRow = grid.removeAt(0)
                if (scrollbackLines.size >= maxScrollbackLines) {
                    scrollbackLines.removeAt(0)
                }
                scrollbackLines.add(topRow)
            }
        } else if (scrollTop < grid.size) {
            grid.removeAt(scrollTop)
        }

        val targetInsert = minOf(scrollBottom, grid.size)
        grid.add(targetInsert, ArrayList())
    }

    private fun reverseIndex() {
        if (cursorRow <= scrollTop) {
            scrollDown()
            cursorRow = scrollTop
        } else {
            cursorRow--
        }
    }

    private fun scrollDown() {
        val grid = activeGrid
        if (scrollBottom < grid.size) {
            grid.removeAt(scrollBottom)
        } else if (grid.isNotEmpty()) {
            grid.removeAt(grid.size - 1)
        }
        val targetInsert = minOf(scrollTop, grid.size)
        grid.add(targetInsert, ArrayList())
    }

    private fun saveCursor() {
        savedCursorRow = cursorRow
        savedCursorCol = cursorCol
        savedColor = currentColor
        savedBgColor = currentBgColor
        savedBold = currentBold
        savedUnderline = currentUnderline
        savedInverse = currentInverse
        savedDim = currentDim
    }

    private fun restoreCursor() {
        cursorRow = savedCursorRow.coerceIn(0, rows - 1)
        cursorCol = savedCursorCol.coerceIn(0, cols - 1)
        currentColor = savedColor
        currentBgColor = savedBgColor
        currentBold = savedBold
        currentUnderline = savedUnderline
        currentInverse = savedInverse
        currentDim = savedDim
    }

    private fun handleCsiSequence(cmd: Char, params: String) {
        when (cmd) {
            'm' -> handleSgr(params)
            'H', 'f' -> { // CUP / HVP: Cursor Position (1-indexed row;col)
                val parts = if (params.isEmpty() || params == ";") listOf(1, 1) else params.split(";").map { it.toIntOrNull() ?: 1 }
                val r = (parts.getOrNull(0) ?: 1) - 1
                val c = (parts.getOrNull(1) ?: 1) - 1
                cursorRow = r.coerceIn(0, rows - 1)
                cursorCol = c.coerceIn(0, cols - 1)
            }
            'A' -> { // CUU: Cursor Up
                val n = params.toIntOrNull() ?: 1
                cursorRow = maxOf(scrollTop, cursorRow - n)
            }
            'B' -> { // CUD: Cursor Down
                val n = params.toIntOrNull() ?: 1
                cursorRow = minOf(scrollBottom, cursorRow + n)
            }
            'C' -> { // CUF: Cursor Forward
                val n = params.toIntOrNull() ?: 1
                cursorCol = minOf(cols - 1, cursorCol + n)
            }
            'D' -> { // CUB: Cursor Backward
                val n = params.toIntOrNull() ?: 1
                cursorCol = maxOf(0, cursorCol - n)
            }
            'E' -> { // CNL: Cursor Next Line
                val n = params.toIntOrNull() ?: 1
                cursorRow = minOf(scrollBottom, cursorRow + n)
                cursorCol = 0
            }
            'F' -> { // CPL: Cursor Previous Line
                val n = params.toIntOrNull() ?: 1
                cursorRow = maxOf(scrollTop, cursorRow - n)
                cursorCol = 0
            }
            'G' -> { // CHA: Cursor Horizontal Absolute (1-indexed)
                val n = params.toIntOrNull() ?: 1
                cursorCol = (n - 1).coerceIn(0, cols - 1)
            }
            'd' -> { // VPA: Line Position Absolute (1-indexed)
                val n = params.toIntOrNull() ?: 1
                cursorRow = (n - 1).coerceIn(0, rows - 1)
            }
            'J' -> { // ED: Erase in Display
                val grid = activeGrid
                when (params) {
                    "", "0" -> { // Clear from cursor to end of screen
                        if (cursorRow < grid.size) {
                            val cur = grid[cursorRow]
                            while (cur.size > cursorCol) cur.removeAt(cur.size - 1)
                            for (r in cursorRow + 1 until grid.size) {
                                grid[r].clear()
                            }
                        }
                    }
                    "1" -> { // Clear from start of screen to cursor
                        for (r in 0 until minOf(cursorRow, grid.size)) {
                            grid[r].clear()
                        }
                        if (cursorRow < grid.size) {
                            val cur = grid[cursorRow]
                            for (c in 0 until minOf(cursorCol + 1, cur.size)) {
                                cur[c] = TerminalChar(' ', 0xFFE0E0E0L, 0x00000000L)
                            }
                        }
                    }
                    "2" -> { // Clear entire screen
                        for (r in 0 until grid.size) {
                            grid[r].clear()
                        }
                        cursorRow = 0
                        cursorCol = 0
                    }
                    "3" -> { // Clear screen and scrollback history
                        scrollbackLines.clear()
                        for (r in 0 until grid.size) {
                            grid[r].clear()
                        }
                        cursorRow = 0
                        cursorCol = 0
                    }
                }
            }
            'K' -> { // EL: Erase in Line
                val grid = activeGrid
                ensureRowCapacity(grid, cursorRow)
                val line = grid[cursorRow]
                when (params) {
                    "", "0" -> { // Clear from cursor to end of line
                        while (line.size > cursorCol) {
                            line.removeAt(line.size - 1)
                        }
                    }
                    "1" -> { // Clear from start of line to cursor
                        for (idx in 0 until minOf(cursorCol, line.size)) {
                            line[idx] = TerminalChar(' ', 0xFFE0E0E0L, 0x00000000L)
                        }
                    }
                    "2" -> { // Clear entire line
                        line.clear()
                        cursorCol = 0
                    }
                }
            }
            'L' -> { // IL: Insert Lines
                val n = params.toIntOrNull() ?: 1
                val grid = activeGrid
                if (cursorRow in scrollTop..scrollBottom) {
                    repeat(n) {
                        if (scrollBottom < grid.size) {
                            grid.removeAt(scrollBottom)
                        }
                        grid.add(cursorRow, ArrayList())
                    }
                }
            }
            'M' -> { // DL: Delete Lines
                val n = params.toIntOrNull() ?: 1
                val grid = activeGrid
                if (cursorRow in scrollTop..scrollBottom) {
                    repeat(n) {
                        if (cursorRow < grid.size) {
                            grid.removeAt(cursorRow)
                        }
                        grid.add(minOf(scrollBottom, grid.size), ArrayList())
                    }
                }
            }
            'P' -> { // DCH: Delete Characters
                val n = params.toIntOrNull() ?: 1
                val grid = activeGrid
                ensureRowCapacity(grid, cursorRow)
                val line = grid[cursorRow]
                repeat(n) {
                    if (cursorCol < line.size) {
                        line.removeAt(cursorCol)
                    }
                }
            }
            '@' -> { // ICH: Insert Characters
                val n = params.toIntOrNull() ?: 1
                val grid = activeGrid
                ensureRowCapacity(grid, cursorRow)
                val line = grid[cursorRow]
                repeat(n) {
                    if (cursorCol <= line.size) {
                        line.add(cursorCol, TerminalChar(' ', 0xFFE0E0E0L, 0x00000000L))
                        if (line.size > cols) line.removeAt(line.size - 1)
                    }
                }
            }
            'r' -> { // DECSTBM: Set Top and Bottom Margins
                val parts = if (params.isEmpty()) listOf(1, rows) else params.split(";").map { it.toIntOrNull() ?: 1 }
                val top = (parts.getOrNull(0) ?: 1) - 1
                val bot = (parts.getOrNull(1) ?: rows) - 1
                scrollTop = top.coerceIn(0, rows - 1)
                scrollBottom = bot.coerceIn(scrollTop, rows - 1)
                cursorRow = 0
                cursorCol = 0
            }
            's' -> saveCursor()
            'u' -> restoreCursor()
            'h' -> { // Private Mode Set (DECSET)
                if (params == "?1049" || params == "?47") {
                    // Enter alternate screen buffer
                    saveCursor()
                    isAlternateScreen = true
                    for (r in alternateScreen) r.clear()
                    cursorRow = 0
                    cursorCol = 0
                } else if (params == "?25") {
                    _isCursorVisible.value = true
                }
            }
            'l' -> { // Private Mode Reset (DECRST)
                if (params == "?1049" || params == "?47") {
                    // Exit alternate screen buffer
                    isAlternateScreen = false
                    restoreCursor()
                } else if (params == "?25") {
                    _isCursorVisible.value = false
                }
            }
        }
    }

    private fun handleSgr(params: String) {
        val tokens = if (params.isEmpty()) listOf(0) else params.split(";").mapNotNull { it.toIntOrNull() }
        var idx = 0
        while (idx < tokens.size) {
            val param = tokens[idx]
            when (param) {
                0 -> { // Reset
                    currentColor = 0xFFE0E0E0L
                    currentBgColor = 0x00000000L
                    currentBold = false
                    currentUnderline = false
                    currentInverse = false
                    currentDim = false
                    idx++
                }
                1 -> { currentBold = true; idx++ }
                2 -> { currentDim = true; idx++ }
                4 -> { currentUnderline = true; idx++ }
                7 -> { currentInverse = true; idx++ }
                22 -> { currentBold = false; currentDim = false; idx++ }
                24 -> { currentUnderline = false; idx++ }
                27 -> { currentInverse = false; idx++ }
                in 30..37 -> { currentColor = ansiColors[param - 30]; idx++ }
                38 -> { // Extended FG
                    if (idx + 2 < tokens.size && tokens[idx + 1] == 5) {
                        currentColor = parse256Color(tokens[idx + 2])
                        idx += 3
                    } else if (idx + 4 < tokens.size && tokens[idx + 1] == 2) {
                        val r = tokens[idx + 2].coerceIn(0, 255)
                        val g = tokens[idx + 3].coerceIn(0, 255)
                        val b = tokens[idx + 4].coerceIn(0, 255)
                        currentColor = 0xFF000000L or ((r and 0xFF).toLong() shl 16) or ((g and 0xFF).toLong() shl 8) or (b and 0xFF).toLong()
                        idx += 5
                    } else {
                        idx++
                    }
                }
                39 -> { currentColor = 0xFFE0E0E0L; idx++ }
                in 40..47 -> { currentBgColor = ansiColors[param - 40]; idx++ }
                48 -> { // Extended BG
                    if (idx + 2 < tokens.size && tokens[idx + 1] == 5) {
                        currentBgColor = parse256Color(tokens[idx + 2])
                        idx += 3
                    } else if (idx + 4 < tokens.size && tokens[idx + 1] == 2) {
                        val r = tokens[idx + 2].coerceIn(0, 255)
                        val g = tokens[idx + 3].coerceIn(0, 255)
                        val b = tokens[idx + 4].coerceIn(0, 255)
                        currentBgColor = 0xFF000000L or ((r and 0xFF).toLong() shl 16) or ((g and 0xFF).toLong() shl 8) or (b and 0xFF).toLong()
                        idx += 5
                    } else {
                        idx++
                    }
                }
                49 -> { currentBgColor = 0x00000000L; idx++ }
                in 90..97 -> { currentColor = brightAnsiColors[param - 90]; idx++ }
                in 100..107 -> { currentBgColor = brightAnsiColors[param - 100]; idx++ }
                else -> idx++
            }
        }
    }

    private fun parse256Color(index: Int): Long {
        if (index in 0..7) return ansiColors[index]
        if (index in 8..15) return brightAnsiColors[index - 8]
        if (index in 16..231) {
            val n = index - 16
            val r = (n / 36) * 51
            val g = ((n / 6) % 6) * 51
            val b = (n % 6) * 51
            return 0xFF000000L or ((r and 0xFF).toLong() shl 16) or ((g and 0xFF).toLong() shl 8) or (b and 0xFF).toLong()
        }
        if (index in 232..255) {
            val gray = 8 + (index - 232) * 10
            return 0xFF000000L or ((gray and 0xFF).toLong() shl 16) or ((gray and 0xFF).toLong() shl 8) or (gray and 0xFF).toLong()
        }
        return 0xFFE0E0E0L
    }

    private fun publishLines() {
        val result = ArrayList<TerminalLineData>()

        if (isAlternateScreen) {
            // In alternate screen mode, publish the exact 2D grid of size 'rows'
            for (r in 0 until rows) {
                val rowChars = if (r < alternateScreen.size) alternateScreen[r] else emptyList()
                result.add(convertRowToLineData(rowChars))
            }
        } else {
            // In primary screen mode, publish scrollback + active rows up to cursor or last non-empty line
            for (lineChars in scrollbackLines) {
                result.add(convertRowToLineData(lineChars))
            }
            val maxRow = maxOf(cursorRow, findLastNonEmptyRow(primaryScreen))
            for (r in 0..maxRow) {
                val rowChars = if (r < primaryScreen.size) primaryScreen[r] else emptyList()
                result.add(convertRowToLineData(rowChars))
            }
        }

        _lines.value = result
        _activeCursorRow.value = cursorRow
        _activeCursorCol.value = cursorCol
    }

    private fun findLastNonEmptyRow(grid: ArrayList<ArrayList<TerminalChar>>): Int {
        for (r in grid.indices.reversed()) {
            if (grid[r].any { it.char != ' ' }) {
                return r
            }
        }
        return 0
    }

    private fun convertRowToLineData(lineChars: List<TerminalChar>): TerminalLineData {
        if (lineChars.isEmpty()) {
            return TerminalLineData(emptyList())
        }

        val spans = ArrayList<StyledSpan>()
        var currentSpanText = StringBuilder()
        var spanColor = lineChars[0].effectiveFg
        var spanBgColor = lineChars[0].effectiveBg
        var spanBold = lineChars[0].isBold
        var spanUnderline = lineChars[0].isUnderline
        var spanInverse = lineChars[0].isInverse

        for (tc in lineChars) {
            val fg = tc.effectiveFg
            val bg = tc.effectiveBg
            if (fg != spanColor || bg != spanBgColor || tc.isBold != spanBold || tc.isUnderline != spanUnderline || tc.isInverse != spanInverse) {
                if (currentSpanText.isNotEmpty()) {
                    spans.add(StyledSpan(currentSpanText.toString(), spanColor, spanBold, spanUnderline, spanInverse, spanBgColor))
                    currentSpanText = StringBuilder()
                }
                spanColor = fg
                spanBgColor = bg
                spanBold = tc.isBold
                spanUnderline = tc.isUnderline
                spanInverse = tc.isInverse
            }
            currentSpanText.append(tc.char)
        }

        if (currentSpanText.isNotEmpty()) {
            spans.add(StyledSpan(currentSpanText.toString(), spanColor, spanBold, spanUnderline, spanInverse, spanBgColor))
        }

        return TerminalLineData(spans)
    }

    /**
     * Extracts the complete scrollback history and active screen as plain text.
     */
    @Synchronized
    fun getPlainText(): String {
        val lines = ArrayList<String>()
        for (row in scrollbackLines) {
            lines.add(row.map { it.char }.joinToString("").trimEnd())
        }
        val grid = activeGrid
        val maxRow = if (isAlternateScreen) rows - 1 else maxOf(cursorRow, findLastNonEmptyRow(grid))
        for (r in 0..maxRow) {
            val row = if (r < grid.size) grid[r] else emptyList()
            lines.add(row.map { it.char }.joinToString("").trimEnd())
        }
        return lines.joinToString("\n")
    }

    /**
     * Extracts the most recent [count] lines of terminal text.
     */
    @Synchronized
    fun getRecentLines(count: Int = 100): List<String> {
        val allLines = getPlainText().split("\n")
        return allLines.takeLast(count)
    }
}
