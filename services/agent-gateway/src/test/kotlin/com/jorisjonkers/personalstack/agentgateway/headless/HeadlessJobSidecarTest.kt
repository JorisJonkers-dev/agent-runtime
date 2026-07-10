package com.jorisjonkers.personalstack.agentgateway.headless

import com.jorisjonkers.personalstack.agentgateway.tmux.AgentKind
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.Instant

class HeadlessJobSidecarTest {
    @Test
    fun `write and read round-trips a completed job`(
        @TempDir tmp: Path,
    ) {
        val createdAt = Instant.parse("2025-01-15T10:00:00Z")
        val completedAt = Instant.parse("2025-01-15T10:05:00Z")
        val outputFile = tmp.resolve("headless-abc12345.jsonl")
        val job =
            HeadlessJob(
                id = "abc12345",
                kind = AgentKind.CLAUDE,
                status = HeadlessJobStatus.COMPLETED,
                outputFile = outputFile,
                exitCode = 0,
                createdAt = createdAt,
                completedAt = completedAt,
            )
        val sidecar = tmp.resolve("headless-abc12345.json")

        HeadlessJobSidecar.write(job, sidecar)
        val reloaded = HeadlessJobSidecar.read(sidecar)

        assertThat(reloaded.id).isEqualTo("abc12345")
        assertThat(reloaded.kind).isEqualTo(AgentKind.CLAUDE)
        assertThat(reloaded.status).isEqualTo(HeadlessJobStatus.COMPLETED)
        assertThat(reloaded.outputFile).isEqualTo(outputFile)
        assertThat(reloaded.exitCode).isEqualTo(0)
        assertThat(reloaded.createdAt).isEqualTo(createdAt)
        assertThat(reloaded.completedAt).isEqualTo(completedAt)
    }

    @Test
    fun `write and read round-trips a failed job with non-zero exit code`(
        @TempDir tmp: Path,
    ) {
        val createdAt = Instant.parse("2025-03-01T08:30:00Z")
        val completedAt = Instant.parse("2025-03-01T08:35:00Z")
        val outputFile = tmp.resolve("headless-fail9876.jsonl")
        val job =
            HeadlessJob(
                id = "fail9876",
                kind = AgentKind.CODEX,
                status = HeadlessJobStatus.FAILED,
                outputFile = outputFile,
                exitCode = 1,
                createdAt = createdAt,
                completedAt = completedAt,
            )
        val sidecar = tmp.resolve("headless-fail9876.json")

        HeadlessJobSidecar.write(job, sidecar)
        val reloaded = HeadlessJobSidecar.read(sidecar)

        assertThat(reloaded.status).isEqualTo(HeadlessJobStatus.FAILED)
        assertThat(reloaded.exitCode).isEqualTo(1)
        assertThat(reloaded.kind).isEqualTo(AgentKind.CODEX)
    }

    @Test
    fun `write and read round-trips a running job with null completedAt and exitCode`(
        @TempDir tmp: Path,
    ) {
        val createdAt = Instant.parse("2025-06-01T12:00:00Z")
        val outputFile = tmp.resolve("headless-run00001.jsonl")
        val job =
            HeadlessJob(
                id = "run00001",
                kind = AgentKind.SHELL,
                status = HeadlessJobStatus.RUNNING,
                outputFile = outputFile,
                exitCode = null,
                createdAt = createdAt,
                completedAt = null,
            )
        val sidecar = tmp.resolve("headless-run00001.json")

        HeadlessJobSidecar.write(job, sidecar)
        val reloaded = HeadlessJobSidecar.read(sidecar)

        assertThat(reloaded.status).isEqualTo(HeadlessJobStatus.RUNNING)
        assertThat(reloaded.exitCode).isNull()
        assertThat(reloaded.completedAt).isNull()
        assertThat(reloaded.kind).isEqualTo(AgentKind.SHELL)
    }

    @Test
    fun `write is idempotent — second write overwrites the first`(
        @TempDir tmp: Path,
    ) {
        val outputFile = tmp.resolve("headless-idem1234.jsonl")
        val job =
            HeadlessJob(
                id = "idem1234",
                kind = AgentKind.CLAUDE,
                status = HeadlessJobStatus.RUNNING,
                outputFile = outputFile,
                createdAt = Instant.now(),
            )
        val sidecar = tmp.resolve("headless-idem1234.json")

        HeadlessJobSidecar.write(job, sidecar)
        val updated = job.copy(status = HeadlessJobStatus.COMPLETED, exitCode = 0, completedAt = Instant.now())
        HeadlessJobSidecar.write(updated, sidecar)

        val reloaded = HeadlessJobSidecar.read(sidecar)
        assertThat(reloaded.status).isEqualTo(HeadlessJobStatus.COMPLETED)
        assertThat(reloaded.exitCode).isEqualTo(0)
    }

    @Test
    fun `written JSON contains expected fields`(
        @TempDir tmp: Path,
    ) {
        val outputFile = tmp.resolve("headless-fmtcheck.jsonl")
        val job =
            HeadlessJob(
                id = "fmtcheck",
                kind = AgentKind.CLAUDE,
                status = HeadlessJobStatus.COMPLETED,
                outputFile = outputFile,
                exitCode = 0,
                createdAt = Instant.parse("2025-01-01T00:00:00Z"),
                completedAt = Instant.parse("2025-01-01T00:01:00Z"),
            )
        val sidecar = tmp.resolve("headless-fmtcheck.json")

        HeadlessJobSidecar.write(job, sidecar)
        val content = sidecar.toFile().readText()

        assertThat(content).contains("\"id\":\"fmtcheck\"")
        assertThat(content).contains("\"kind\":\"CLAUDE\"")
        assertThat(content).contains("\"status\":\"COMPLETED\"")
        assertThat(content).contains("\"exitCode\":0")
        assertThat(content).contains("\"createdAt\"")
        assertThat(content).contains("\"completedAt\"")
    }
}
