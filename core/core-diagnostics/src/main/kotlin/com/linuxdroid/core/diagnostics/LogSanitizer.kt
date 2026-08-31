package com.linuxdroid.core.diagnostics

/**
 * Sanitizes logs and diagnostic reports to remove sensitive credentials,
 * API tokens, cookies, and passwords while strictly preserving technical debug paths.
 */
object LogSanitizer {

    private val SENSITIVE_KEY_PATTERN = Regex(
        "(?i)(api[_-]?key|secret|token|password|passwd|auth[_-]?token|bearer[_-]?token)\\s*[:=]\\s*([\"']?[A-Za-z0-9_\\-\\.\\/+=]{8,}[\"']?)"
    )

    private val BEARER_PATTERN = Regex(
        "(?i)bearer\\s+[A-Za-z0-9_\\-\\.\\/+=]{15,}"
    )

    private val COOKIE_PATTERN = Regex(
        "(?i)(Set-Cookie|Cookie):\\s*([^\\r\\n]+)"
    )

    private val QUERY_PARAM_PATTERN = Regex(
        "(?i)([?&](?:password|passwd|token|api[_-]?key|secret)=)([^&\\s]+)"
    )

    /**
     * Sanitizes a single log line.
     */
    fun sanitize(text: String): String {
        if (text.isBlank()) return text
        var result = text
        result = BEARER_PATTERN.replace(result, "Bearer [REDACTED]")
        result = COOKIE_PATTERN.replace(result, "$1: [REDACTED]")
        result = QUERY_PARAM_PATTERN.replace(result, "$1[REDACTED]")
        result = SENSITIVE_KEY_PATTERN.replace(result) { match ->
            val key = match.groupValues[1]
            "$key=[REDACTED]"
        }
        return result
    }

    /**
     * Sanitizes a collection of log lines.
     */
    fun sanitizeLines(lines: List<String>): List<String> = lines.map { sanitize(it) }
}

