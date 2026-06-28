@file:Suppress("LongMethod")

package com.jorisjonkers.personalstack.agentgateway.tmux

import io.micrometer.observation.Observation
import io.micrometer.observation.ObservationRegistry
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
    private val onStart: (TranscriptTailerStartResult) -> Unit = {},
    private val observationRegistry: ObservationRegistry = ObservationRegistry.NOOP,
    private val onText: (TranscriptTextFrame) -> Unit,
) : AutoCloseable {
    private val log = LoggerFactory.getLogger(TranscriptTailer::class.java)
    private var offset = startOffset
    private var carry = ByteArray(0)

    private val executor: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "transcript-tailer-$stableSessionId").apply { isDaemon = true }
        }

    fun start(): TranscriptTailerStartResult {
        val result =
            runCatching {
                executor.scheduleWithFixedDelay(::poll, 0, intervalMs, TimeUnit.MILLISECONDS)
                TranscriptTailerStartResult(success = true)
            }.getOrElse {
                TranscriptTailerStartResult(success = false, failureReason = failureReason(it))
            }
        observeStart(result)
        onStart(result)
        return result
    }

    fun replayAvailable(): TranscriptReplayResult {
        val observation = Observation.start("agent.gateway.transcript.replay", observationRegistry)
        var bytes = 0L
        var frames = 0L
        while (true) {
            val before = offset
            val result = pollOnce()
            bytes += result.bytes
            frames += result.frames
            if (result.failureReason != null) {
                return finishReplayObservation(
                    observation,
                    TranscriptReplayResult(
                        bytes = bytes,
                        frames = frames,
                        success = false,
                        failureReason = result.failureReason,
                    ),
                )
            }
            if (offset == before) {
                return finishReplayObservation(
                    observation,
                    TranscriptReplayResult(bytes = bytes, frames = frames, success = true),
                )
            }
        }
    }

    private fun poll() {
        val result = pollOnce()
        if (result.failureReason != null) {
            log.warn("tail of transcript {} failed: {}", stableSessionId, result.failureReason)
        }
    }

    private fun pollOnce(): TranscriptPollResult =
        runCatching { pollOnceUnchecked() }.getOrElse {
            TranscriptPollResult(failureReason = failureReason(it))
        }

    @Suppress("ReturnCount")
    private fun pollOnceUnchecked(): TranscriptPollResult {
        val read = store.readRaw(stableSessionId, offset)
        if (read.startOffset > offset) {
            carry = ByteArray(0)
            offset = read.startOffset
            onTrim(offset)
        }
        if (read.bytes.isEmpty()) return TranscriptPollResult()

        val readEnd = read.startOffset + read.bytes.size
        val buf = if (carry.isEmpty()) read.bytes else carry + read.bytes
        val complete = LogTailer.completeUtf8Length(buf)
        carry = if (complete < buf.size) buf.copyOfRange(complete, buf.size) else EMPTY
        offset = readEnd
        if (complete == 0) return TranscriptPollResult()

        val completeEnd = readEnd - carry.size
        val completeStart = completeEnd - complete
        var frameStart = completeStart
        var bytes = 0L
        var frames = 0L
        var failure: String? = null
        LogTailer.chunked(String(buf, 0, complete, Charsets.UTF_8), maxChunkChars) { text ->
            if (failure != null) return@chunked
            val frameBytes = text.toByteArray(Charsets.UTF_8).size
            frameStart += frameBytes
            bytes += frameBytes
            frames++
            runCatching { onText(TranscriptTextFrame(output = text, off = frameStart)) }
                .onFailure { failure = failureReason(it) }
        }
        return TranscriptPollResult(bytes = bytes, frames = frames, failureReason = failure)
    }

    override fun close() {
        executor.shutdownNow()
        executor.awaitTermination(1, TimeUnit.SECONDS)
    }

    private fun failureReason(error: Throwable): String {
        val name = error.javaClass.simpleName
        val message = error.message?.takeIf { it.isNotBlank() }
        return listOfNotNull(name, message).joinToString(": ").take(MAX_FAILURE_REASON_CHARS)
    }

    private fun observeStart(result: TranscriptTailerStartResult) {
        Observation
            .start("agent.gateway.transcript.tailer.start", observationRegistry)
            .lowCardinalityKeyValue("outcome", if (result.success) "success" else "failure")
            .lowCardinalityKeyValue("reason", if (result.success) "none" else "other")
            .stop()
    }

    private fun finishReplayObservation(
        observation: Observation,
        result: TranscriptReplayResult,
    ): TranscriptReplayResult {
        observation
            .lowCardinalityKeyValue("outcome", if (result.success) "success" else "failure")
            .lowCardinalityKeyValue("reason", if (result.success) "none" else "other")
            .stop()
        return result
    }

    companion object {
        private val EMPTY = ByteArray(0)
        private const val MAX_FAILURE_REASON_CHARS = 160
    }
}

data class TranscriptTextFrame(
    val output: String,
    val off: Long,
)

data class TranscriptReplayResult(
    val bytes: Long,
    val frames: Long,
    val success: Boolean,
    val failureReason: String? = null,
)

data class TranscriptTailerStartResult(
    val success: Boolean,
    val failureReason: String? = null,
)

private data class TranscriptPollResult(
    val bytes: Long = 0,
    val frames: Long = 0,
    val failureReason: String? = null,
)
