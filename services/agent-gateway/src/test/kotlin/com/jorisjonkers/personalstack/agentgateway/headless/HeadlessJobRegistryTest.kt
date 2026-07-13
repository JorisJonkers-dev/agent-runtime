package com.jorisjonkers.personalstack.agentgateway.headless

import com.jorisjonkers.personalstack.agentgateway.observability.AgentGatewayTelemetry
import com.jorisjonkers.personalstack.agentgateway.tmux.AgentKind
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class HeadlessJobRegistryTest {
    @Test
    fun `updateUnlessCancelled does not overwrite cancelled jobs`(
        @TempDir tmp: Path,
    ) {
        val registry = registry(tmp)
        val job = runningJob(tmp, "cace1111")
        registry.register(job)

        val cancelled = registry.markStatus(job.id, HeadlessJobStatus.CANCELLED)
        val completed = registry.updateUnlessCancelled(job.id, HeadlessJobStatus.COMPLETED, 0)

        assertThat(cancelled!!.status).isEqualTo(HeadlessJobStatus.CANCELLED)
        assertThat(completed!!.status).isEqualTo(HeadlessJobStatus.CANCELLED)
        assertThat(registry.get(job.id)!!.status).isEqualTo(HeadlessJobStatus.CANCELLED)
        assertThat(registry.get(job.id)!!.exitCode).isNull()
    }

    @Test
    fun `concurrent terminal updates leave one consistent persisted state`(
        @TempDir tmp: Path,
    ) {
        val registry = registry(tmp)
        val job = runningJob(tmp, "face2222")
        registry.register(job)
        val ready = CountDownLatch(2)
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)

        val completion =
            executor.submit {
                ready.countDown()
                start.await(3, TimeUnit.SECONDS)
                registry.updateUnlessCancelled(job.id, HeadlessJobStatus.COMPLETED, 0)
            }
        val cancellation =
            executor.submit {
                ready.countDown()
                start.await(3, TimeUnit.SECONDS)
                registry.markStatus(job.id, HeadlessJobStatus.CANCELLED)
            }
        assertThat(ready.await(3, TimeUnit.SECONDS)).isTrue()
        start.countDown()
        executor.shutdown()
        assertThat(executor.awaitTermination(3, TimeUnit.SECONDS)).isTrue()
        completion.get()
        cancellation.get()

        val current = registry.get(job.id)!!
        val persisted = HeadlessJobSidecar.read(tmp.resolve("headless-${job.id}.json"))
        assertThat(persisted.status).isEqualTo(current.status)
        assertThat(persisted.exitCode).isEqualTo(current.exitCode)
        assertThat(current.completedAt).isNotNull()
        assertThat(current.status).isEqualTo(HeadlessJobStatus.CANCELLED)
    }

    @Test
    fun `reload converts running sidecars to failed jobs`(
        @TempDir tmp: Path,
    ) {
        val job = runningJob(tmp, "dead3333")
        HeadlessJobSidecar.write(job, tmp.resolve("headless-${job.id}.json"))

        val registry = registry(tmp)
        registry.reload()

        val reloaded = registry.get(job.id)
        assertThat(reloaded).isNotNull()
        assertThat(reloaded!!.status).isEqualTo(HeadlessJobStatus.FAILED)
        assertThat(reloaded.completedAt).isNotNull()
    }

    private fun registry(tmp: Path): HeadlessJobRegistry =
        HeadlessJobRegistry(
            stateDir = tmp,
            telemetry = HeadlessJobTelemetry(AgentGatewayTelemetry.NOOP),
        )

    private fun runningJob(
        tmp: Path,
        id: String,
    ): HeadlessJob {
        val outputFile = tmp.resolve("headless-$id.jsonl")
        outputFile.toFile().createNewFile()
        return HeadlessJob(
            id = id,
            kind = AgentKind.CLAUDE,
            status = HeadlessJobStatus.RUNNING,
            outputFile = outputFile,
            createdAt = Instant.now(),
        )
    }
}
