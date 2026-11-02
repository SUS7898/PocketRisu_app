package com.sus7898.pocketrisu.log

/**
 * Masks secrets before a message is stored anywhere. Rules are documented in docs/LOGGING.md §3.
 * Applied at log time, so neither the ring buffer nor the log files ever hold the original.
 */
object Redactor {
    private const val REDACTED = "<redacted>"

    // "risu-auth: eyJ..." / "Cookie=..." — header name followed by its whole value.
    private val headerValue = Regex(
        """(?i)\b(risu-auth|authorization|cookie|set-cookie|x-session-id|x-goog-api-key|x-api-key)\b\s*[:=]\s*[^\r\n]+""",
    )

    // risu-session=<token> inside a cookie string or a URL.
    private val sessionCookie = Regex("""(?i)risu-session=[^;\s&]+""")

    // PEM private key (service-account JSON "private_key": "-----BEGIN PRIVATE KEY-----\n…").
    // Console lines are truncated (300 chars) so the END marker may be missing — then to the end.
    private val pemKey = Regex(
        """-----BEGIN [A-Z ]*PRIVATE KEY-----(?:[\s\S]*?-----END [A-Z ]*PRIVATE KEY-----|[\s\S]*)""",
    )

    // Provider keys that show up bare in error text: OpenAI/Anthropic-style sk-…, Google AIza….
    private val providerKey = Regex("""\b(?:sk-[A-Za-z0-9_-]{16,}|AIza[0-9A-Za-z_-]{30,})""")

    // "Bearer <token>" outside a header line.
    private val bearer = Regex("""(?i)\bBearer\s+[A-Za-z0-9._~+/-]{16,}=*""")

    // key=value / "key": "value" / key: value for credential-like keys, including prefixed names
    // (refresh_token, access_token, client_secret, private_key) — \b alone misses those because
    // '_' is a word character.
    private val credentialPair = Regex(
        // (?!<) — a value already replaced by an earlier rule (<private-key>, <redacted>) is left alone.
        """(?i)\b((?:[a-z0-9]+[_-])*(?:password|passwd|pwd|token|secret|api[_-]?key|private[_-]?key)|risu-auth)\b"?\s*[=:]\s*"?(?!<)([^\s&,;"'}]+)""",
    )

    // Three base64url segments of 20+ chars each: a JWT, wherever it appears.
    private val jwt = Regex("""\b[A-Za-z0-9_-]{20,}\.[A-Za-z0-9_-]{20,}\.[A-Za-z0-9_-]{20,}\b""")

    fun redact(message: String): String {
        if (message.isEmpty()) return message
        var out = headerValue.replace(message) { m -> "${m.groupValues[1]}: $REDACTED" }
        // Before credentialPair: that rule would eat "-----BEGIN" as the value of private_key and
        // leave the key body behind.
        out = pemKey.replace(out, "<private-key>")
        out = providerKey.replace(out, REDACTED)
        out = bearer.replace(out, "Bearer $REDACTED")
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
