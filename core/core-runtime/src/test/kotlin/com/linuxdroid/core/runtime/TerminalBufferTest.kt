package com.linuxdroid.core.runtime

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class TerminalBufferTest {

    @Test
    fun `plain text append produces correct line data`() {
        val buffer = TerminalBuffer(maxScrollbackLines = 100)
        val text = "LinuxDroid Shell Ready\r\n"
        buffer.append(text.toByteArray(), text.length)

        val lines = buffer.lines.value
        assertThat(lines).isNotEmpty()
        assertThat(lines[0].rawText).isEqualTo("LinuxDroid Shell Ready")
    }

    @Test
    fun `ANSI color escape codes create styled spans`() {
        val buffer = TerminalBuffer(maxScrollbackLines = 100)
        // \u001B[32m is Green (0xFF81C784), \u001B[0m is Reset
        val coloredText = "\u001B[32mroot@linuxdroid\u001B[0m:~$ \r\n"
        buffer.append(coloredText.toByteArray(), coloredText.length)

        val lines = buffer.lines.value
        assertThat(lines).isNotEmpty()
        val line = lines[0]
        assertThat(line.rawText).contains("root@linuxdroid:~$")

        val greenSpan = line.spans.find { it.text == "root@linuxdroid" }
        assertThat(greenSpan).isNotNull()
        assertThat(greenSpan?.color).isEqualTo(0xFF81C784)
    }

    @Test
    fun `carriage return and line break handle cursor positions`() {
        val buffer = TerminalBuffer(maxScrollbackLines = 100)
        val text = "Line 1\r\nLine 2\r\n"
        buffer.append(text.toByteArray(), text.length)

        val lines = buffer.lines.value
        assertThat(lines.size).isAtLeast(2)
        assertThat(lines[0].rawText).isEqualTo("Line 1")
        assertThat(lines[1].rawText).isEqualTo("Line 2")
    }

    @Test
    fun `backspace and overwrite updates character at cursor`() {
        val buffer = TerminalBuffer(maxScrollbackLines = 100)
        // "abc", move back 1, overwrite with 'z' -> "abz"
        val text = "abc\bz\r\n"
        buffer.append(text.toByteArray(), text.length)

        val lines = buffer.lines.value
        assertThat(lines).isNotEmpty()
        assertThat(lines[0].rawText).isEqualTo("abz")
    }

    @Test
    fun `erase in line escape sequence clears remaining text`() {
        val buffer = TerminalBuffer(maxScrollbackLines = 100)
        // "hello world", backspace 5 times, \u001B[K (erase to end) -> "hello "
        val text = "hello world\b\b\b\b\b\u001B[K\r\n"
        buffer.append(text.toByteArray(), text.length)

        val lines = buffer.lines.value
        assertThat(lines).isNotEmpty()
        assertThat(lines[0].rawText).isEqualTo("hello ")
    }

    @Test
    fun `clear resets the terminal buffer`() {
        val buffer = TerminalBuffer(maxScrollbackLines = 100)
        val text = "First line\r\nSecond line\r\n"
        buffer.append(text.toByteArray(), text.length)
        assertThat(buffer.lines.value.size).isAtLeast(2)

        buffer.clear()
        val linesAfter = buffer.lines.value
        assertThat(linesAfter.size).isEqualTo(1)
        assertThat(linesAfter[0].rawText).isEmpty()
    }

    @Test
    fun `getPlainText extracts full scrollback without ANSI escapes`() {
        val buffer = TerminalBuffer(maxScrollbackLines = 100)
        val text = "\u001B[32muser@host\u001B[0m:~$ command failed\r\nError code 127\r\n"
        buffer.append(text.toByteArray(), text.length)

        val plainText = buffer.getPlainText()
        assertThat(plainText).contains("user@host:~$ command failed")
        assertThat(plainText).contains("Error code 127")
        assertThat(plainText).doesNotContain("\u001B[32m")
    }

    @Test
    fun `streaming multi-byte UTF-8 split across chunk boundaries decodes cleanly without replacement chars`() {
        val buffer = TerminalBuffer(maxScrollbackLines = 100)
        // Emoji 🚀 is 4 bytes in UTF-8: F0 9F 9A 80
        val emojiBytes = "Hello 🚀 World!\r\n".toByteArray(Charsets.UTF_8)
        // Split right inside the 4-byte sequence: "Hello " + [F0, 9F] in chunk 1, [9A, 80] + " World!\r\n" in chunk 2
        val splitIndex = "Hello ".toByteArray(Charsets.UTF_8).size + 2
        val chunk1 = emojiBytes.copyOfRange(0, splitIndex)
        val chunk2 = emojiBytes.copyOfRange(splitIndex, emojiBytes.size)

        buffer.append(chunk1, chunk1.size)
        buffer.append(chunk2, chunk2.size)

        val plainText = buffer.getPlainText()
        assertThat(plainText).contains("Hello 🚀 World!")
        assertThat(plainText).doesNotContain("\uFFFD")
    }

    @Test
    fun `2D cursor positioning moves to absolute row and column`() {
        val buffer = TerminalBuffer(maxScrollbackLines = 100)
        buffer.resize(24, 80)
        // Move to row 5, col 10 (1-indexed: \u001B[5;10H), write "Target"
        val cmd = "\u001B[5;10HTarget"
        buffer.append(cmd.toByteArray(), cmd.length)

        assertThat(buffer.activeCursorRow.value).isEqualTo(4) // 0-indexed row 4
        assertThat(buffer.activeCursorCol.value).isEqualTo(15) // col 9 + 6 = 15

        val lines = buffer.lines.value
        assertThat(lines.size).isAtLeast(5)
        val targetLine = lines[4].rawText
        assertThat(targetLine).startsWith("         Target")
    }

    @Test
    fun `alternate screen buffer isolates curses TUI from scrollback`() {
        val buffer = TerminalBuffer(maxScrollbackLines = 100)
        buffer.resize(24, 80)

        // 1. Output normal shell text
        val normalText = "Normal shell prompt $ ls\r\nfile1.txt  file2.txt\r\n"
        buffer.append(normalText.toByteArray(), normalText.length)
        assertThat(buffer.isAlternateScreen).isFalse()

        // 2. Enter alternate screen (vim/htop: \u001B[?1049h)
        val enterAlt = "\u001B[?1049h\u001B[1;1H~ VIM EDITOR SCREEN ~\u001B[24;1H-- INSERT --"
        buffer.append(enterAlt.toByteArray(), enterAlt.length)
        assertThat(buffer.isAlternateScreen).isTrue()

        val altLines = buffer.lines.value
        assertThat(altLines.size).isEqualTo(24)
        assertThat(altLines[0].rawText).contains("~ VIM EDITOR SCREEN ~")
        assertThat(altLines[23].rawText).contains("-- INSERT --")

        // 3. Exit alternate screen (\u001B[?1049l)
        val exitAlt = "\u001B[?1049l"
        buffer.append(exitAlt.toByteArray(), exitAlt.length)
        assertThat(buffer.isAlternateScreen).isFalse()

        // Verify original shell history is fully restored without vim text
        val restoredText = buffer.getPlainText()
        assertThat(restoredText).contains("Normal shell prompt $ ls")
        assertThat(restoredText).contains("file1.txt  file2.txt")
        assertThat(restoredText).doesNotContain("VIM EDITOR SCREEN")
    }

    @Test
    fun `extended 256 colors and TrueColor render accurate colors`() {
        val buffer = TerminalBuffer(maxScrollbackLines = 100)
        // 256 color: \u001B[38;5;196m (Bright Red), TrueColor: \u001B[38;2;100;150;200m
        val colored = "\u001B[38;5;196mColor256\u001B[0m \u001B[38;2;100;150;200mTrueColor\u001B[0m\r\n"
        buffer.append(colored.toByteArray(), colored.length)

        val lines = buffer.lines.value
        assertThat(lines).isNotEmpty()
        val spans = lines[0].spans

        val span256 = spans.find { it.text == "Color256" }
        assertThat(span256).isNotNull()

        val spanTrue = spans.find { it.text == "TrueColor" }
        assertThat(spanTrue).isNotNull()
        // 0xFF000000 or (100 shl 16) or (150 shl 8) or 200
        val expectedTrueColor = 0xFF000000L or (100L shl 16) or (150L shl 8) or 200L
        assertThat(spanTrue?.color).isEqualTo(expectedTrueColor)
    }
}
