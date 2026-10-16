package com.sus7898.pocketrisu.log

/**
 * Masks secrets before a message is stored anywhere. Rules are documented in docs/LOGGING.md §3.
 * Applied at log time, so neither the ring buffer nor the log files ever hold the original.
 */
object Redactor {
    private const val REDACTED = "<redacted>"

    // "risu-auth: eyJ..." / "Cookie=..." — header name followed by its whole value.
    private val headerValue = Regex(
        """(?i)\b(risu-auth|authorization|cookie|set-cookie|x-session-id)\b\s*[:=]\s*[^\r\n]+""",
    )

    // risu-session=<token> inside a cookie string or a URL.
    private val sessionCookie = Regex("""(?i)risu-session=[^;\s&]+""")

    // key=value / "key": "value" / key: value for credential-like keys.
    private val credentialPair = Regex(
        """(?i)\b(password|passwd|pwd|token|secret|api[_-]?key|risu-auth)\b"?\s*[=:]\s*"?([^\s&,;"'}]+)""",
    )

    // Three base64url segments of 20+ chars each: a JWT, wherever it appears.
    private val jwt = Regex("""\b[A-Za-z0-9_-]{20,}\.[A-Za-z0-9_-]{20,}\.[A-Za-z0-9_-]{20,}\b""")

    fun redact(message: String): String {
        if (message.isEmpty()) return message
        var out = headerValue.replace(message) { m -> "${m.groupValues[1]}: $REDACTED" }
        out = sessionCookie.replace(out, "risu-session=$REDACTED")
        out = credentialPair.replace(out) { m ->
            val whole = m.value
            // Keep everything up to the value, replace only the value.
            whole.substring(0, whole.length - m.groupValues[2].length) + REDACTED
        }
        out = jwt.replace(out, "<jwt>")
        return out
    }

    /** Replaces the configured server host with a placeholder (export-time rule). */
    fun maskHost(text: String, host: String?): String {
        if (host.isNullOrBlank()) return text
        return text.replace(host, "<server>", ignoreCase = true)
    }
}
