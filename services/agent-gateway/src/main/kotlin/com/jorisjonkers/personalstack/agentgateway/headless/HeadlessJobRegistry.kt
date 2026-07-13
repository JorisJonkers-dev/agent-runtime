package com.jorisjonkers.personalstack.agentgateway.headless

import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

internal class HeadlessJobRegistry(
    private val stateDir: Path,
    private val telemetry: HeadlessJobTelemetry,
) {
    private val log = LoggerFactory.getLogger(HeadlessJobRegistry::class.java)
    private val jobs = ConcurrentHashMap<String, HeadlessJob>()

    fun reload() {
        if (!Files.isDirectory(stateDir)) return
        runCatching {
            Files.list(stateDir).use { entries ->
                entries
                    .filter { it.fileName.toString().matches(SIDECAR_FILE_PATTERN) }
                    .forEach { sidecar -> reloadSidecar(sidecar) }
            }
        }.onFailure { ex ->
            log.warn("headless job state reload failed: {}", ex.message)
        }
        log.info("headless job registry reloaded {} entries from {}", jobs.size, stateDir)
    }

    @Synchronized
    fun register(job: HeadlessJob): HeadlessJob {
        jobs[job.id] = job
        persistSidecar(job)
        telemetry.recordCounts(jobs.values)
        return job
    }

    fun get(id: String): HeadlessJob? = jobs[id]

    fun list(): List<HeadlessJob> = jobs.values.sortedBy { it.createdAt }

    fun readOutput(
        id: String,
        maxChars: Int,
    ): String {
        val job = jobs[id] ?: return ""
        return runCatching {
            val text = Files.readString(job.outputFile, Charsets.UTF_8)
            if (text.length <= maxChars) text else "\u2026" + text.takeLast(maxChars)
        }.getOrDefault("")
    }

    @Synchronized
    fun updateUnlessCancelled(
        id: String,
        status: HeadlessJobStatus,
        exitCode: Int,
    ): HeadlessJob? {
        var updated: HeadlessJob? = null
        jobs.compute(id) { _, job ->
            val next =
                job
                    ?.takeUnless { it.status == HeadlessJobStatus.CANCELLED }
                    ?.copy(status = status, exitCode = exitCode, completedAt = Instant.now())
                    ?: job
            updated = next
            next
        }
        afterTransition(updated)
        return updated
    }

    @Synchronized
    fun markStatus(
        id: String,
        status: HeadlessJobStatus,
    ): HeadlessJob? {
        var updated: HeadlessJob? = null
        jobs.compute(id) { _, job ->
            job?.copy(status = status, completedAt = Instant.now()).also { updated = it }
        }
        afterTransition(updated)
        return updated
    }

    fun duration(job: HeadlessJob): Duration {
        val completedAt = job.completedAt ?: Instant.now()
        return Duration.between(job.createdAt, completedAt)
    }

    private fun reloadSidecar(sidecar: Path) {
        runCatching {
            val job = HeadlessJobSidecar.read(sidecar)
            val recovered =
                if (job.status == HeadlessJobStatus.RUNNING) {
                    job.copy(status = HeadlessJobStatus.FAILED, completedAt = job.completedAt ?: Instant.now())
                } else {
                    job
                }
            if (Files.exists(recovered.outputFile)) {
                jobs[recovered.id] = recovered
                if (recovered != job) {
                    persistSidecar(recovered)
                }
            }
        }.onFailure { ex ->
            log.warn("headless job sidecar {} could not be reloaded: {}", sidecar.fileName, ex.message)
        }
    }

    private fun afterTransition(job: HeadlessJob?) {
        job?.let { persistSidecar(it) }
        telemetry.recordCounts(jobs.values)
    }

    private fun persistSidecar(job: HeadlessJob) {
        runCatching {
            HeadlessJobSidecar.write(job, stateDir.resolve("headless-${job.id}.json"))
        }.onFailure { ex ->
            log.warn("headless job {} sidecar write failed: {}", job.id, ex.message)
        }
    }

    private companion object {
        val SIDECAR_FILE_PATTERN = Regex("headless-[0-9a-fA-F]+\\.json")
    }
}
