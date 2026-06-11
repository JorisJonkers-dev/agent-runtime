package com.jorisjonkers.personalstack.agentgateway.headless

import com.jorisjonkers.personalstack.agentgateway.config.GatewayProperties
import com.jorisjonkers.personalstack.agentgateway.tmux.AgentKind
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.DisposableBean
import org.springframework.stereotype.Component
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Async registry for one-shot (headless) agent runs. Each job launches
 * the CLI as a child process with stdin closed, streams stdout+stderr
 * to a JSONL capture file in the gateway state dir, and updates status
 * when the process exits or times out.
 *
 * Concurrency: one virtual thread per job; job state is updated via
 * `ConcurrentHashMap.compute` so concurrent status reads are safe.
 * Cancellation kills the OS process and marks the job CANCELLED.
 *
 * The job registry is in-memory — Pod restarts lose running jobs. The
 * caller (agents-api) should detect a missing job id and surface it
 * as a FAILED status.
 */
@Component
class HeadlessJobManager(
    private val props: GatewayProperties,
    private val processFactory: ProcessFactory = DefaultProcessFactory,
) : DisposableBean {
    private val log = LoggerFactory.getLogger(HeadlessJobManager::class.java)
    private val jobs = ConcurrentHashMap<String, HeadlessJob>()
    private val processes = ConcurrentHashMap<String, Process>()
    private val executor: ExecutorService = Executors.newVirtualThreadPerTaskExecutor()

    fun launch(
        kind: AgentKind,
        prompt: String,
        workspacePath: String? = null,
        cliSessionId: String? = null,
        timeoutSeconds: Long = DEFAULT_TIMEOUT_SECONDS,
    ): HeadlessJob {
        val id = UUID.randomUUID().toString().substring(0, 8)
        val cwd = File(workspacePath ?: props.workspaceRoot)
        val stateDir = Path.of(props.tmux.stateDir).also { Files.createDirectories(it) }
        val outputFile = stateDir.resolve("headless-$id.jsonl")
        Files.createFile(outputFile)
        val command = headlessCommandFor(kind, prompt, cliSessionId)
        val job =
            HeadlessJob(
                id = id,
                kind = kind,
                status = HeadlessJobStatus.RUNNING,
                outputFile = outputFile,
                createdAt = Instant.now(),
            )
        jobs[id] = job
        executor.submit { runJob(id, command, cwd, outputFile.toFile(), timeoutSeconds) }
        log.info("launched headless {} job {} in {}", kind, id, cwd)
        return job
    }

    fun get(id: String): HeadlessJob? = jobs[id]

    fun list(): List<HeadlessJob> = jobs.values.sortedBy { it.createdAt }

    fun cancel(id: String): Boolean {
        val process = processes.remove(id) ?: return jobs.containsKey(id)
        process.destroyForcibly()
        jobs.compute(id) { _, job ->
            job?.copy(status = HeadlessJobStatus.CANCELLED, completedAt = Instant.now())
        }
        log.info("cancelled headless job {}", id)
        return true
    }

    fun readOutput(
        id: String,
        maxChars: Int = MAX_OUTPUT_CHARS,
    ): String {
        val job = jobs[id] ?: return ""
        return runCatching {
            val bytes = Files.readAllBytes(job.outputFile)
            val text = String(bytes, Charsets.UTF_8)
            if (text.length <= maxChars) text else "…" + text.takeLast(maxChars)
        }.getOrDefault("")
    }

    override fun destroy() {
        executor.shutdownNow()
    }

    private fun runJob(
        id: String,
        command: List<String>,
        cwd: File,
        outputFile: File,
        timeoutSeconds: Long,
    ) {
        val process = startProcess(id, command, cwd) ?: return
        processes[id] = process
        try {
            awaitAndCapture(id, process, outputFile, timeoutSeconds)
        } catch (ex: InterruptedException) {
            process.destroyForcibly()
            jobs.compute(id) { _, job ->
                job?.copy(status = HeadlessJobStatus.CANCELLED, completedAt = Instant.now())
            }
        } finally {
            processes.remove(id)
        }
    }

    private fun startProcess(
        id: String,
        command: List<String>,
        cwd: File,
    ): Process? =
        runCatching {
            processFactory.start(command, cwd)
        }.getOrElse { ex ->
            log.error("headless job {} failed to start: {}", id, ex.message)
            jobs.compute(id) { _, job ->
                job?.copy(status = HeadlessJobStatus.FAILED, completedAt = Instant.now())
            }
            null
        }

    private fun awaitAndCapture(
        id: String,
        process: Process,
        outputFile: File,
        timeoutSeconds: Long,
    ) {
        // Async gobbler keeps the pipe drained so waitFor never hangs on a full buffer.
        val gobbler = Thread.ofVirtual().start { process.inputStream.copyTo(outputFile.outputStream()) }
        val finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
        gobbler.join(GOBBLER_JOIN_MS)
        if (!finished) {
            process.destroyForcibly()
            updateJob(id, HeadlessJobStatus.FAILED, TIMEOUT_EXIT_CODE)
            log.warn("headless job {} timed out after {}s", id, timeoutSeconds)
        } else {
            val exitCode = process.exitValue()
            val status = if (exitCode == 0) HeadlessJobStatus.COMPLETED else HeadlessJobStatus.FAILED
            updateJob(id, status, exitCode)
            log.info("headless job {} finished status={} exitCode={}", id, status, exitCode)
        }
    }

    private fun updateJob(
        id: String,
        status: HeadlessJobStatus,
        exitCode: Int,
    ) {
        jobs.compute(id) { _, job ->
            job?.copy(status = status, exitCode = exitCode, completedAt = Instant.now())
        }
    }

    /**
     * Build the one-shot CLI command for a headless run. Claude uses
     * `-p` (print mode) with `--output-format stream-json` for machine-
     * parseable streaming events. Codex uses `exec --json` plus the
     * configured Codex CLI args; when a `cliSessionId` is explicitly
     * provided it resumes that exact context via `exec resume <id> --json`.
     */
    private fun headlessCommandFor(
        kind: AgentKind,
        prompt: String,
        cliSessionId: String?,
    ): List<String> =
        when (kind) {
            AgentKind.CLAUDE ->
                listOf(
                    props.cli.claude,
                    "-p",
                    "--output-format",
                    "stream-json",
                ) + (cliSessionId?.let { listOf("--resume", it) } ?: emptyList()) +
                    listOf("--", prompt)

            AgentKind.CODEX ->
                if (cliSessionId != null) {
                    listOf(props.cli.codex, "exec", "resume") +
                        props.cli.codexArgs +
                        listOf("--json", cliSessionId, "--", prompt)
                } else {
                    listOf(props.cli.codex, "exec") +
                        props.cli.codexArgs +
                        listOf("--json", "--", prompt)
                }

            AgentKind.SHELL -> listOf("/bin/sh", "-c", prompt)
        }

    fun interface ProcessFactory {
        fun start(
            command: List<String>,
            cwd: File,
        ): Process
    }

    companion object {
        const val DEFAULT_TIMEOUT_SECONDS = 600L
        const val MAX_OUTPUT_CHARS = 65_536
        private const val TIMEOUT_EXIT_CODE = -1
        private const val GOBBLER_JOIN_MS = 2_000L

        val DefaultProcessFactory =
            ProcessFactory { command, cwd ->
                ProcessBuilder(command)
                    .directory(cwd)
                    .redirectErrorStream(true)
                    .start()
            }
    }
}
