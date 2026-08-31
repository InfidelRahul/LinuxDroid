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
}
