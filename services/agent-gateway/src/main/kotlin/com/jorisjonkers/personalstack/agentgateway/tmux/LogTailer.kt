package com.jorisjonkers.personalstack.agentgateway.tmux

import org.slf4j.LoggerFactory
import java.io.RandomAccessFile
import java.nio.file.Path
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Polls an append-only log file every `intervalMs` and streams any new
 * output as bounded text chunks. tmux's pipe-pane writes raw pane output
 * here, so this is the streaming-from-PTY mechanism without needing a
 * fifo or a JNI tmux library.
 *
 * The output is relayed straight through to the browser, never buffered
 * whole, so the terminal stream costs no standing heap. Two details make
 * that safe:
 *
 *  - **Bounded frames.** Each emitted chunk is at most [maxChunkChars]
 *    characters, so a noisy agent that prints megabytes streams as a
 *    series of bounded frames rather than one frame that would force a
 *    multi-megabyte receive buffer per session. The bound is sized so a
 *    JSON-wrapped frame still fits the WebSocket text buffer the bridge
 *    raises for the output leg (see agents-api `SessionAttachHandler`);
 *    keeping it generous means a TUI's screenful of redraws ships in a
 *    couple of frames instead of dozens of 1 KiB ones, which is what made
 *    rendering lag — every frame is a separate parse + write downstream.
 *  - **UTF-8 boundary carry.** A read can end midway through a multi-byte
 *    codepoint (the box-drawing glyphs a TUI emits are three bytes); the
 *    trailing partial bytes are held back and prepended to the next read
 *    so a character is never decoded — or split across frames — in halves.
 *
 * Single-tailer-per-file model: each WS attach gets its own tailer
 * starting at the current end of file, so a freshly-attached client
 * streams only new bytes. The whole-screen snapshot that gives the client
 * its initial state is sent separately on attach. If the session's log is
 * truncated to stay under its disk cap the tailer restarts from the new
 * beginning rather than stalling.
 */
class LogTailer(
    private val file: Path,
    private val intervalMs: Long = 40,
    private val maxChunkChars: Int = MAX_CHUNK_CHARS,
    private val onText: (String) -> Unit,
) : AutoCloseable {
    private val log = LoggerFactory.getLogger(LogTailer::class.java)
    private val offset = AtomicLong(0)

    // Trailing bytes of an incomplete UTF-8 sequence held back from the
    // previous read until the rest of the codepoint arrives.
    private var carry = ByteArray(0)

    private val executor: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "log-tailer-${file.fileName}").apply { isDaemon = true }
        }

    fun start() {
        offset.set(currentLength())
        executor.scheduleWithFixedDelay(::poll, 0, intervalMs, TimeUnit.MILLISECONDS)
    }

    private fun currentLength(): Long =
        try {
            RandomAccessFile(file.toFile(), "r").use { it.length() }
        } catch (e: java.io.IOException) {
            log.warn("sizing {} failed: {}", file, e.message)
            0L
        }

    private fun poll() {
        try {
            RandomAccessFile(file.toFile(), "r").use { raf ->
                val length = raf.length()
                var currentOffset = offset.get()
                if (length < currentOffset) {
                    // The log was truncated to stay under its disk cap;
                    // restart from the new beginning.
                    offset.set(0)
                    carry = ByteArray(0)
                    currentOffset = 0
                }
                if (length <= currentOffset) return
                raf.seek(currentOffset)
                val toRead = (length - currentOffset).coerceAtMost(MAX_READ_BYTES.toLong()).toInt()
                val raw = ByteArray(toRead)
                val read = raf.read(raw)
                if (read <= 0) return
                offset.addAndGet(read.toLong())
                emit(raw, read)
            }
        } catch (e: java.io.IOException) {
            log.warn("tail of {} failed: {}", file, e.message)
        }
    }

    private fun emit(
        raw: ByteArray,
        read: Int,
    ) {
        val buf = if (carry.isEmpty()) raw.copyOf(read) else carry + raw.copyOf(read)
        val complete = completeUtf8Length(buf)
        carry = if (complete < buf.size) buf.copyOfRange(complete, buf.size) else EMPTY
        if (complete == 0) return
        chunked(String(buf, 0, complete, Charsets.UTF_8), maxChunkChars, onText)
    }

    override fun close() {
        executor.shutdownNow()
    }

    companion object {
        const val MAX_CHUNK_CHARS = 16 * 1024
        private const val MAX_READ_BYTES = 64 * 1024
        private const val BYTE_TO_UNSIGNED_MASK = 0xFF
        private const val NO_UTF8_LEAD_BYTE = -1
        private const val UTF8_CONTINUATION_MASK = 0xC0
        private const val UTF8_CONTINUATION_PREFIX = 0x80
        private const val UTF8_SINGLE_BYTE_LIMIT = 0x80
        private const val UTF8_TWO_BYTE_LEAD_START = 0xC0
        private const val UTF8_TWO_BYTE_LEAD_END = 0xDF
        private const val UTF8_THREE_BYTE_LEAD_START = 0xE0
        private const val UTF8_THREE_BYTE_LEAD_END = 0xEF
        private const val UTF8_FOUR_BYTE_LEAD_START = 0xF0
        private const val UTF8_FOUR_BYTE_LEAD_END = 0xF7
        private const val UTF8_SINGLE_BYTE_SEQUENCE_LENGTH = 1
        private const val UTF8_TWO_BYTE_SEQUENCE_LENGTH = 2
        private const val UTF8_THREE_BYTE_SEQUENCE_LENGTH = 3
        private const val UTF8_FOUR_BYTE_SEQUENCE_LENGTH = 4
        private val EMPTY = ByteArray(0)

        /**
         * Index of the first byte of an incomplete trailing UTF-8
         * sequence, or `buf.size` if the buffer ends on a complete
         * codepoint. Malformed lead bytes are treated as complete so the
         * decoder substitutes them rather than the carry growing forever.
         */
        @Suppress("ReturnCount")
        internal fun completeUtf8Length(buf: ByteArray): Int {
            if (buf.isEmpty()) return 0
            val leadIndex = trailingLeadByteIndex(buf)
            if (leadIndex == NO_UTF8_LEAD_BYTE) return buf.size
            val availableSequenceBytes = buf.size - leadIndex
            val expectedSequenceBytes = utf8SequenceLength(buf[leadIndex].toUnsignedInt())
            return if (availableSequenceBytes >= expectedSequenceBytes) buf.size else leadIndex
        }

        private fun trailingLeadByteIndex(buf: ByteArray): Int {
            var i = buf.size - 1
            while (i >= 0 && buf[i].isUtf8Continuation()) {
                i--
            }
            return i
        }

        private fun Byte.isUtf8Continuation(): Boolean {
            val prefix = toInt() and UTF8_CONTINUATION_MASK
            return prefix == UTF8_CONTINUATION_PREFIX
        }

        private fun Byte.toUnsignedInt(): Int = toInt() and BYTE_TO_UNSIGNED_MASK

        private fun utf8SequenceLength(lead: Int): Int =
            when {
                lead < UTF8_SINGLE_BYTE_LIMIT -> UTF8_SINGLE_BYTE_SEQUENCE_LENGTH
                lead in UTF8_TWO_BYTE_LEAD_START..UTF8_TWO_BYTE_LEAD_END -> UTF8_TWO_BYTE_SEQUENCE_LENGTH
                lead in UTF8_THREE_BYTE_LEAD_START..UTF8_THREE_BYTE_LEAD_END -> UTF8_THREE_BYTE_SEQUENCE_LENGTH
                lead in UTF8_FOUR_BYTE_LEAD_START..UTF8_FOUR_BYTE_LEAD_END -> UTF8_FOUR_BYTE_SEQUENCE_LENGTH
                else -> UTF8_SINGLE_BYTE_SEQUENCE_LENGTH
            }

        /**
         * Feeds [text] to [action] in pieces of at most [maxChars],
         * never splitting a surrogate pair across two pieces.
         */
        internal fun chunked(
            text: String,
            maxChars: Int,
            action: (String) -> Unit,
        ) {
            var i = 0
            while (i < text.length) {
                var end = (i + maxChars).coerceAtMost(text.length)
                if (end < text.length && Character.isHighSurrogate(text[end - 1])) end--
                action(text.substring(i, end))
                i = end
            }
        }
    }
}
