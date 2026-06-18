package com.jorisjonkers.personalstack.agentgateway.headless

import com.jorisjonkers.personalstack.agentgateway.tmux.AgentKind
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class HeadlessOutputStreamerTest {
    @Test
    fun `streams appended complete lines once and then emits done`(
        @TempDir tmp: Path,
    ) {
        val file = tmp.resolve("headless-job.jsonl")
        Files.createFile(file)
        val job =
            HeadlessJob(
                id = "job-1",
                kind = AgentKind.CODEX,
                status = HeadlessJobStatus.RUNNING,
                outputFile = file,
                createdAt = Instant.parse("2026-01-01T00:00:00Z"),
            )
        val currentJob = AtomicReference(job)
        val sink = RecordingSink()

        HeadlessOutputStreamer(
            job = job,
            currentJob = { currentJob.get() },
            sink = sink,
            intervalMs = 20,
        ).use {
            it.start()

            Files.writeString(file, """{"type":"token","text":"hel"}""", StandardOpenOption.APPEND)
            Thread.sleep(60)
            assertThat(sink.events).isEmpty()

            Files.writeString(
                file,
                "\n" + """{"type":"token","text":"lo"}""" + "\n",
                StandardOpenOption.APPEND,
            )
            await().atMost(Duration.ofSeconds(2)).until {
                sink.events.count { event -> event.name == "line" } == 2
            }

            currentJob.set(
                job.copy(
                    status = HeadlessJobStatus.COMPLETED,
                    exitCode = 0,
                    completedAt = Instant.parse("2026-01-01T00:00:02Z"),
                ),
            )

            await().atMost(Duration.ofSeconds(2)).until { sink.completed.get() }
        }

        assertThat(sink.events)
            .containsExactly(
                StreamEvent("line", """{"type":"token","text":"hel"}"""),
                StreamEvent("line", """{"type":"token","text":"lo"}"""),
                StreamEvent("done", """{"status":"COMPLETED","exitCode":0}"""),
            )
        assertThat(sink.error.get()).isNull()
    }

    private data class StreamEvent(
        val name: String,
        val data: String,
    )

    private class RecordingSink : HeadlessOutputStreamer.EventSink {
        val events = CopyOnWriteArrayList<StreamEvent>()
        val completed = AtomicBoolean(false)
        val error = AtomicReference<Throwable?>()

        override fun send(
            name: String,
            data: String,
        ) {
            events.add(StreamEvent(name, data))
        }

        override fun complete() {
            completed.set(true)
        }

        override fun completeWithError(error: Throwable) {
            this.error.set(error)
        }
    }
}
