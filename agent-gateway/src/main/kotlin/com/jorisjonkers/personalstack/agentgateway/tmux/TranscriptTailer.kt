package com.jorisjonkers.personalstack.agentgateway.tmux

import org.slf4j.LoggerFactory
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

class TranscriptTailer(
    private val store: TranscriptStore,
    private val stableSessionId: String,
    startOffset: Long,
    private val intervalMs: Long = 40,
    private val maxChunkChars: Int = LogTailer.MAX_CHUNK_CHARS,
    private val onTrim: (Long) -> Unit = {},
    private val onText: (TranscriptTextFrame) -> Unit,
) : AutoCloseable {
    private val log = LoggerFactory.getLogger(TranscriptTailer::class.java)
    private var offset = startOffset
    private var carry = ByteArray(0)

    private val executor: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "transcript-tailer-$stableSessionId").apply { isDaemon = true }
        }

    fun start() {
        executor.scheduleWithFixedDelay(::poll, 0, intervalMs, TimeUnit.MILLISECONDS)
    }

    fun replayAvailable() {
        while (true) {
            val before = offset
            poll()
            if (offset == before) return
        }
    }

    private fun poll() {
        runCatching {
            val read = store.readRaw(stableSessionId, offset)
            if (read.startOffset > offset) {
                carry = ByteArray(0)
                offset = read.startOffset
                onTrim(offset)
            }
            if (read.bytes.isEmpty()) return

            val readEnd = read.startOffset + read.bytes.size
            val buf = if (carry.isEmpty()) read.bytes else carry + read.bytes
            val complete = LogTailer.completeUtf8Length(buf)
            carry = if (complete < buf.size) buf.copyOfRange(complete, buf.size) else EMPTY
            offset = readEnd
            if (complete == 0) return

            val completeEnd = readEnd - carry.size
            val completeStart = completeEnd - complete
            var frameStart = completeStart
            LogTailer.chunked(String(buf, 0, complete, Charsets.UTF_8), maxChunkChars) { text ->
                frameStart += text.toByteArray(Charsets.UTF_8).size
                onText(TranscriptTextFrame(output = text, off = frameStart))
            }
        }.onFailure {
            log.warn("tail of transcript {} failed: {}", stableSessionId, it.message)
        }
    }

    override fun close() {
        executor.shutdownNow()
    }

    companion object {
        private val EMPTY = ByteArray(0)
    }
}

data class TranscriptTextFrame(
    val output: String,
    val off: Long,
)
