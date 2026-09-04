package com.linuxdroid.core.package_mgr

/**
 * Parses FreeDesktop .desktop `Exec` command strings into a tokenized `argv` list
 * according to the FreeDesktop Desktop Entry Specification.
 *
 * Handles single/double quoting, backslash escaping, and field-code expansion/stripping.
 */
object DesktopExecParser {

    /**
     * Parses an `Exec` line into an argument list (argv).
     *
     * @param exec The raw Exec string from the .desktop entry
     * @param icon Optional icon path to substitute for %i
     * @param name Optional app name to substitute for %c
     * @param desktopFilePath Optional desktop file path to substitute for %k
     * @return List of command line arguments ready for execve / ProcessBuilder
     */
    fun parse(
        exec: String,
        icon: String? = null,
        name: String? = null,
        desktopFilePath: String? = null,
    ): List<String> {
        val trimmed = exec.trim()
        if (trimmed.isEmpty()) return emptyList()

        val tokens = mutableListOf<String>()
        val current = StringBuilder()
        var inSingleQuote = false
        var inDoubleQuote = false
        var escaped = false

        var i = 0
        while (i < trimmed.length) {
            val c = trimmed[i]

            if (escaped) {
                current.append(c)
                escaped = false
                i++
                continue
            }

            if (c == '\\' && !inSingleQuote) {
                if (inDoubleQuote) {
                    // Inside double quotes, only \, ", $, ` are escaped according to spec
                    if (i + 1 < trimmed.length && trimmed[i + 1] in listOf('\\', '"', '$', '`')) {
                        escaped = true
                        i++
                        continue
                    } else {
                        current.append('\\')
                        i++
                        continue
                    }
                } else {
                    escaped = true
                    i++
                    continue
                }
            }

            if (c == '\'' && !inDoubleQuote) {
                inSingleQuote = !inSingleQuote
                i++
                continue
            }

            if (c == '"' && !inSingleQuote) {
                inDoubleQuote = !inDoubleQuote
                i++
                continue
            }

            if (!inSingleQuote && !inDoubleQuote) {
                if (c.isWhitespace()) {
                    if (current.isNotEmpty()) {
                        tokens.add(current.toString())
                        current.setLength(0)
                    }
                    i++
                    continue
                }

                // Handle field codes outside quotes
                if (c == '%') {
                    if (i + 1 < trimmed.length) {
                        val next = trimmed[i + 1]
                        when (next) {
                            '%' -> {
                                current.append('%')
                                i += 2
                                continue
                            }
                            'i' -> {
                                if (!icon.isNullOrBlank()) {
                                    if (current.isNotEmpty()) {
                                        tokens.add(current.toString())
                                        current.setLength(0)
                                    }
                                    tokens.add("--icon")
                                    tokens.add(icon)
                                }
                                i += 2
                                continue
                            }
                            'c' -> {
                                if (!name.isNullOrBlank()) {
                                    current.append(name)
                                }
                                i += 2
                                continue
                            }
                            'k' -> {
                                if (!desktopFilePath.isNullOrBlank()) {
                                    current.append(desktopFilePath)
                                }
                                i += 2
                                continue
                            }
                            // All other field codes (%f, %F, %u, %U, %d, %D, %n, %N, %v, etc.)
                            // are file/URL parameters or deprecated; drop them when launching standalone
                            in 'a'..'z', in 'A'..'Z' -> {
                                i += 2
                                continue
                            }
                            else -> {
                                current.append('%')
                                i++
                                continue
                            }
                        }
                    } else {
                        current.append('%')
                        i++
                        continue
                    }
                }
            } else if (inDoubleQuote && c == '%') {
                // Double quotes also support field codes
                if (i + 1 < trimmed.length) {
                    val next = trimmed[i + 1]
                    when (next) {
                        '%' -> {
                            current.append('%')
                            i += 2
                            continue
                        }
                        'c' -> {
                            if (!name.isNullOrBlank()) current.append(name)
                            i += 2
                            continue
                        }
                        'k' -> {
                            if (!desktopFilePath.isNullOrBlank()) current.append(desktopFilePath)
                            i += 2
                            continue
                        }
                        in 'a'..'z', in 'A'..'Z' -> {
                            i += 2
                            continue
                        }
                        else -> {
                            current.append('%')
                            i++
                            continue
                        }
                    }
                }
            }

            current.append(c)
            i++
        }

        if (current.isNotEmpty()) {
            tokens.add(current.toString())
        }

        return tokens.filter { it.isNotBlank() }
    }
}
