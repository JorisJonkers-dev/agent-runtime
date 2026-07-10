package com.jorisjonkers.personalstack.agentgateway.headless

import com.jorisjonkers.personalstack.agentgateway.tmux.AgentKind
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Instant

/**
 * Simple hand-rolled JSON sidecar format for [HeadlessJob] state.
 *
 * One file per job (`headless-<id>.json`) in the gateway state dir.
 * Written on every status change so the most recent on-disk state is
 * always consistent with the last update. Read back at startup to
 * reconstitute the job registry after a Pod restart.
 *
 * The format is deliberately minimal — no Jackson dependency —
 * because the only consumers are [HeadlessJobManager.afterPropertiesSet]
 * (read) and [HeadlessJobManager.persistSidecar] (write).
 *
 * Field order in the serialised object is stable so diff-based tooling
 * produces clean output.
 */
internal object HeadlessJobSidecar {
    fun write(
        job: HeadlessJob,
        sidecar: Path,
    ) {
        val json =
            buildString {
                append('{')
                appendStringField("id", job.id)
                append(',')
                appendStringField("kind", job.kind.name)
                append(',')
                appendStringField("status", job.status.name)
                append(',')
                appendStringField("outputFile", job.outputFile.toString())
                append(',')
                appendStringField("createdAt", job.createdAt.toString())
                append(',')
                if (job.completedAt != null) {
                    appendStringField("completedAt", job.completedAt.toString())
                    append(',')
                }
                if (job.exitCode != null) {
                    append('"')
                    append("exitCode")
                    append('"')
                    append(':')
                    append(job.exitCode)
                } else {
                    append('"')
                    append("exitCode")
                    append('"')
                    append(':')
                    append("null")
                }
                append('}')
            }
        Files.writeString(
            sidecar,
            json,
            Charsets.UTF_8,
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING,
        )
    }

    fun read(sidecar: Path): HeadlessJob {
        val text = Files.readString(sidecar, Charsets.UTF_8)
        val fields = parseJsonObject(text)

        val id = fields["id"] ?: error("sidecar missing 'id': $sidecar")
        val kind = AgentKind.valueOf(fields["kind"] ?: error("sidecar missing 'kind': $sidecar"))
        val status = HeadlessJobStatus.valueOf(fields["status"] ?: error("sidecar missing 'status': $sidecar"))
        val outputFile = Path.of(fields["outputFile"] ?: error("sidecar missing 'outputFile': $sidecar"))
        val createdAt = Instant.parse(fields["createdAt"] ?: error("sidecar missing 'createdAt': $sidecar"))
        val completedAt = fields["completedAt"]?.let { Instant.parse(it) }
        val exitCode = fields["exitCode"]?.takeIf { it != "null" }?.toIntOrNull()

        return HeadlessJob(
            id = id,
            kind = kind,
            status = status,
            outputFile = outputFile,
            createdAt = createdAt,
            completedAt = completedAt,
            exitCode = exitCode,
        )
    }

    /**
     * Minimal hand-rolled JSON object parser. Handles the small fixed schema
     * produced by [write] — string values, integer exitCode, null exitCode.
     * Does not handle nested objects, arrays, or escaped characters beyond
     * what the stdlib [Instant.toString] and [Path.toString] produce.
     */
    private fun parseJsonObject(text: String): Map<String, String?> {
        val result = mutableMapOf<String, String?>()
        // Strip outer braces
        val body = text.trim().removePrefix("{").removeSuffix("}")
        var pos = 0
        while (pos < body.length) {
            // skip whitespace and commas
            while (pos < body.length && (body[pos] == ',' || body[pos].isWhitespace())) pos++
            if (pos >= body.length) break
            // read key
            val key = readString(body, pos) ?: break
            pos += key.second
            // skip colon
            while (pos < body.length && (body[pos] == ':' || body[pos].isWhitespace())) pos++
            // read value — either a quoted string, null, or an integer
            val (value, advance) = parseValue(body, pos)
            result[key.first] = value
            pos += advance
        }
        return result
    }

    private fun parseValue(
        body: String,
        pos: Int,
    ): Pair<String?, Int> {
        if (pos >= body.length) return null to 0
        return when {
            body[pos] == '"' -> {
                val str = readString(body, pos)
                str?.first to (str?.second ?: 0)
            }
            body.startsWith("null", pos) -> null to 4
            else -> {
                // integer — advance until non-digit
                var end = pos
                while (end < body.length && (body[end].isDigit() || body[end] == '-')) end++
                body.substring(pos, end) to (end - pos)
            }
        }
    }

    /**
     * Reads a JSON-quoted string at position [start] in [text].
     * Returns the unquoted value and the number of characters consumed,
     * or null if there is no quoted string at [start].
     */
    private fun readString(
        text: String,
        start: Int,
    ): Pair<String, Int>? {
        if (start >= text.length || text[start] != '"') return null
        var pos = start + 1
        val sb = StringBuilder()
        while (pos < text.length && text[pos] != '"') {
            if (text[pos] == '\\' && pos + 1 < text.length) {
                sb.append(unescape(text[pos + 1]))
                pos += 2
            } else {
                sb.append(text[pos])
                pos++
            }
        }
        if (pos < text.length) pos++ // consume closing '"'
        return sb.toString() to (pos - start)
    }

    private fun unescape(ch: Char): Char =
        when (ch) {
            '"' -> '"'
            '\\' -> '\\'
            'n' -> '\n'
            'r' -> '\r'
            't' -> '\t'
            else -> ch
        }
}

private fun StringBuilder.appendStringField(
    key: String,
    value: String,
) {
    append('"')
    append(key)
    append('"')
    append(':')
    append('"')
    // Escape backslashes and quotes within the value
    for (ch in value) {
        when (ch) {
            '\\' -> append("\\\\")
            '"' -> append("\\\"")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> append(ch)
        }
    }
    append('"')
}
