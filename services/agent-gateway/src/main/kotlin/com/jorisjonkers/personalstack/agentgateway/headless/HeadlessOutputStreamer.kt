package com.jorisjonkers.personalstack.agentgateway.headless

import org.slf4j.LoggerFactory
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import java.io.RandomAccessFile
import java.nio.file.Path
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class HeadlessOutputStreamer private constructor(
    private val runner: Runner,
) : AutoCloseable {
    constructor(
        job: HeadlessJob,
        currentJob: (String) -> HeadlessJob?,
        emitter: SseEmitter,
        intervalMs: Long = DEFAULT_INTERVAL_MS,
    ) : this(Runner(job, currentJob, SseEventSink(emitter), intervalMs)) {
        emitter.onCompletion { close() }
        emitter.onTimeout { close() }
        emitter.onError { _ -> close() }
    }

    internal constructor(
        job: HeadlessJob,
        currentJob: (String) -> HeadlessJob?,
        sink: EventSink,
        intervalMs: Long = DEFAULT_INTERVAL_MS,
    ) : this(Runner(job, currentJob, sink, intervalMs))

    fun start() {
        runner.start()
    }

    override fun close() {
        runner.close()
    }

    internal interface EventSink {
        fun send(
            name: String,
            data: String,
        )

        fun complete()

        fun completeWithError(error: Throwable)
    }

    private class SseEventSink(
        private val emitter: SseEmitter,
    ) : EventSink {
        override fun send(
            name: String,
            data: String,
        ) {
            // Send the raw line/payload verbatim (text/plain). Using APPLICATION_JSON
            // here would re-serialize the already-JSON string, double-encoding it.
            emitter.send(SseEmitter.event().name(name).data(data))
        }

        override fun complete() {
            emitter.complete()
        }

        override fun completeWithError(error: Throwable) {
            emitter.completeWithError(error)
        }
    }

    private class Runner(
        val job: HeadlessJob,
        val currentJob: (String) -> HeadlessJob?,
        private val sink: EventSink,
        val intervalMs: Long,
    ) : AutoCloseable {
        private val log = LoggerFactory.getLogger(HeadlessOutputStreamer::class.java)
        private val offset = AtomicLong(0)
        private val stopped = AtomicBoolean(false)
        private var pending = ByteArray(0)
        private val executor: ScheduledExecutorService =
            Executors.newSingleThreadScheduledExecutor { r ->
                Thread(r, "headless-output-streamer-${job.id}").apply { isDaemon = true }
            }

        fun start() {
            executor.scheduleWithFixedDelay(::poll, 0, intervalMs, TimeUnit.MILLISECONDS)
        }

        private fun poll() {
            if (stopped.get()) return
            runCatching {
                val current = currentJob(job.id) ?: job
                drainCompleteLines(job.outputFile)
                if (current.status != HeadlessJobStatus.RUNNING && isFullyRead(job.outputFile)) {
                    sink.send("done", donePayload(current))
                    sink.complete()
                    stop()
                }
            }.onFailure { ex ->
                log.warn("headless output stream for job {} failed: {}", job.id, ex.message)
                sink.completeWithError(ex)
                stop()
            }
        }

        private fun drainCompleteLines(file: Path) {
            RandomAccessFile(file.toFile(), "r").use { raf ->
                val length = raf.length()
                val currentOffset = offset.get()
                if (length > currentOffset) {
                    raf.seek(currentOffset)
                    val toRead = (length - currentOffset).coerceAtMost(MAX_READ_BYTES.toLong()).toInt()
                    val raw = ByteArray(toRead)
                    val read = raf.read(raw)
                    if (read > 0) {
                        offset.addAndGet(read.toLong())
                        drainBufferedBytes(raw, read)
                    }
                }
            }
        }

        private fun drainBufferedBytes(
            raw: ByteArray,
            read: Int,
        ) {
            val buffer = if (pending.isEmpty()) raw.copyOf(read) else pending + raw.copyOf(read)
            val complete = completeLineBytes(buffer, buffer.size)
            if (complete == 0) {
                pending = buffer
            } else {
                emitLines(String(buffer, 0, complete, Charsets.UTF_8))
                pending =
                    if (complete < buffer.size) {
                        buffer.copyOfRange(complete, buffer.size)
                    } else {
                        EMPTY
                    }
            }
        }

        private fun emitLines(text: String) {
            text
                .removeSuffix("\n")
                .split('\n')
                .forEach { line -> sink.send("line", line.removeSuffix("\r")) }
        }

        private fun isFullyRead(file: Path): Boolean =
            RandomAccessFile(file.toFile(), "r").use { raf ->
                raf.length() <= offset.get()
            }

        private fun donePayload(job: HeadlessJob): String {
            val exitCode = job.exitCode ?: "null"
            return """{"status":"${job.status}","exitCode":$exitCode}"""
        }

        private fun completeLineBytes(
            raw: ByteArray,
            read: Int,
        ): Int {
            var i = read - 1
            while (i >= 0) {
                if (raw[i] == NEWLINE_BYTE) return i + 1
                i--
            }
            return 0
        }

        private fun stop() {
            if (stopped.compareAndSet(false, true)) {
                executor.shutdown()
            }
        }

        override fun close() {
            stopped.set(true)
            executor.shutdownNow()
        }
    }

    companion object {
        const val DEFAULT_INTERVAL_MS = 100L
        private const val TIMEOUT_MARGIN_SECONDS = 30L
        const val TIMEOUT_MILLIS = (HeadlessJobManager.DEFAULT_TIMEOUT_SECONDS + TIMEOUT_MARGIN_SECONDS) * 1_000L
        private const val MAX_READ_BYTES = 64 * 1024
        private const val NEWLINE_BYTE: Byte = 10
        private val EMPTY = ByteArray(0)
    }
}
