package com.jorisjonkers.personalstack.agentgateway.headless

import com.jorisjonkers.personalstack.agentgateway.config.GatewayProperties
import com.jorisjonkers.personalstack.agentgateway.observability.AgentGatewayTelemetry
import com.jorisjonkers.personalstack.agentgateway.observability.GatewayActiveSessionsSample
import com.jorisjonkers.personalstack.agentgateway.observability.GatewayAgentKindLabel
import com.jorisjonkers.personalstack.agentgateway.observability.GatewayFailureReasonLabel
import com.jorisjonkers.personalstack.agentgateway.observability.GatewayModeLabel
import com.jorisjonkers.personalstack.agentgateway.observability.GatewayOperationLabel
import com.jorisjonkers.personalstack.agentgateway.observability.GatewayOperationTelemetry
import com.jorisjonkers.personalstack.agentgateway.observability.GatewayOutcomeLabel
import com.jorisjonkers.personalstack.agentgateway.observability.GatewayStatusLabel
import com.jorisjonkers.personalstack.agentgateway.tmux.AgentKind
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.DisposableBean
import org.springframework.stereotype.Component
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
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
@Suppress("TooManyFunctions")
@Component
class HeadlessJobManager(
    private val props: GatewayProperties,
    private val telemetry: AgentGatewayTelemetry = AgentGatewayTelemetry.NOOP,
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
    ): HeadlessJob =
        launch(
            HeadlessLaunchRequest(
                kind = kind,
                prompt = prompt,
                workspacePath = workspacePath,
                cliSessionId = cliSessionId,
                timeoutSeconds = timeoutSeconds,
            ),
        )

    fun launch(request: HeadlessLaunchRequest): HeadlessJob {
        val id = UUID.randomUUID().toString().substring(0, 8)
        val cwd = File(request.workspacePath ?: props.workspaceRoot)
        val stateDir = Path.of(props.tmux.stateDir).also { Files.createDirectories(it) }
        val outputFile = stateDir.resolve("headless-$id.jsonl")
        Files.createFile(outputFile)
        val command = headlessCommandFor(request.kind, request.prompt, request.cliSessionId, request.partialMessages)
        val job =
            HeadlessJob(
                id = id,
                kind = request.kind,
                status = HeadlessJobStatus.RUNNING,
                outputFile = outputFile,
                createdAt = Instant.now(),
            )
        jobs[id] = job
        recordJobCounts()
        recordHeadlessJobEvent(
            kind = request.kind,
            operation = GatewayOperationLabel.SPAWN,
            outcome = GatewayOutcomeLabel.SUCCESS,
            duration = Duration.between(job.createdAt, Instant.now()),
        )
        executor.submit {
            runJob(
                HeadlessRunContext(
                    id = id,
                    kind = request.kind,
                    command = command,
                    cwd = cwd,
                    outputFile = outputFile.toFile(),
                    timeoutSeconds = request.timeoutSeconds,
                ),
            )
        }
        log.info("launched headless {} job {} in {}", request.kind, id, cwd)
        return job
    }

    fun get(id: String): HeadlessJob? = jobs[id]

    fun list(): List<HeadlessJob> = jobs.values.sortedBy { it.createdAt }

    fun cancel(id: String): Boolean {
        val process =
            processes.remove(id)
                ?: return jobs[id]?.let { job ->
                    recordHeadlessJobEvent(
                        kind = job.kind,
                        operation = GatewayOperationLabel.STOP,
                        outcome = GatewayOutcomeLabel.SKIPPED,
                        duration = Duration.ZERO,
                    )
                    true
                } ?: false
        process.destroyForcibly()
        val cancelled = markJob(id, HeadlessJobStatus.CANCELLED)
        cancelled?.let {
            recordHeadlessJobEvent(
                kind = it.kind,
                operation = GatewayOperationLabel.STOP,
                outcome = GatewayOutcomeLabel.CANCELLED,
                reason = GatewayFailureReasonLabel.CANCELLED,
                duration = jobDuration(it),
            )
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

    private fun runJob(context: HeadlessRunContext) {
        val process =
            startProcess(context.id, context.kind, context.command, context.cwd)
                ?: run {
                    recordCleanup(context.kind, GatewayOutcomeLabel.SKIPPED, GatewayFailureReasonLabel.UNKNOWN)
                    return
                }
        processes[context.id] = process
        try {
            awaitAndCapture(context.id, process, context.outputFile, context.timeoutSeconds)
        } catch (ex: InterruptedException) {
            process.destroyForcibly()
            val cancelled = markJob(context.id, HeadlessJobStatus.CANCELLED)
            cancelled?.let {
                recordHeadlessJobEvent(
                    kind = it.kind,
                    operation = GatewayOperationLabel.HEADLESS_JOB,
                    outcome = GatewayOutcomeLabel.CANCELLED,
                    reason = GatewayFailureReasonLabel.CANCELLED,
                    duration = jobDuration(it),
                )
            }
        } finally {
            val removed = processes.remove(context.id)
            val reason =
                if (jobs[context.id]?.status == HeadlessJobStatus.CANCELLED) {
                    GatewayFailureReasonLabel.CANCELLED
                } else {
                    GatewayFailureReasonLabel.NONE
                }
            recordCleanup(
                kind = context.kind,
                outcome = if (removed == null) GatewayOutcomeLabel.SKIPPED else GatewayOutcomeLabel.SUCCESS,
                reason = reason,
            )
        }
    }

    private fun startProcess(
        id: String,
        kind: AgentKind,
        command: List<String>,
        cwd: File,
    ): Process? =
        runCatching {
            processFactory.start(command, cwd)
        }.getOrElse { ex ->
            log.error("headless job {} failed to start: {}", id, ex.message)
            val failed = markJob(id, HeadlessJobStatus.FAILED)
            failed?.let {
                recordHeadlessJobEvent(
                    kind = kind,
                    operation = GatewayOperationLabel.SPAWN,
                    outcome = GatewayOutcomeLabel.FAILURE,
                    reason = startFailureReason(ex),
                    duration = jobDuration(it),
                )
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
            val failed = updateJob(id, HeadlessJobStatus.FAILED, TIMEOUT_EXIT_CODE)
            failed?.takeUnless { it.status == HeadlessJobStatus.CANCELLED }?.let {
                recordHeadlessJobEvent(
                    kind = it.kind,
                    operation = GatewayOperationLabel.HEADLESS_JOB,
                    outcome = GatewayOutcomeLabel.FAILURE,
                    reason = GatewayFailureReasonLabel.TIMEOUT,
                    duration = jobDuration(it),
                )
            }
            log.warn("headless job {} timed out after {}s", id, timeoutSeconds)
        } else {
            val exitCode = process.exitValue()
            val status = if (exitCode == 0) HeadlessJobStatus.COMPLETED else HeadlessJobStatus.FAILED
            val updated = updateJob(id, status, exitCode)
            updated?.takeUnless { it.status == HeadlessJobStatus.CANCELLED }?.let {
                recordHeadlessJobEvent(
                    kind = it.kind,
                    operation = GatewayOperationLabel.HEADLESS_JOB,
                    outcome =
                        if (status == HeadlessJobStatus.COMPLETED) {
                            GatewayOutcomeLabel.SUCCESS
                        } else {
                            GatewayOutcomeLabel.FAILURE
                        },
                    reason =
                        if (status == HeadlessJobStatus.COMPLETED) {
                            GatewayFailureReasonLabel.NONE
                        } else {
                            GatewayFailureReasonLabel.PROCESS_EXITED
                        },
                    duration = jobDuration(it),
                )
            }
            log.info("headless job {} finished status={} exitCode={}", id, status, exitCode)
        }
    }

    private fun updateJob(
        id: String,
        status: HeadlessJobStatus,
        exitCode: Int,
    ): HeadlessJob? {
        var updated: HeadlessJob? = null
        jobs.compute(id) { _, job ->
            job
                ?.takeUnless { it.status == HeadlessJobStatus.CANCELLED }
                ?.copy(status = status, exitCode = exitCode, completedAt = Instant.now())
                ?.also { updated = it }
                ?: job.also { updated = it }
        }
        recordJobCounts()
        return updated
    }

    private fun markJob(
        id: String,
        status: HeadlessJobStatus,
    ): HeadlessJob? {
        var updated: HeadlessJob? = null
        jobs.compute(id) { _, job ->
            job?.copy(status = status, completedAt = Instant.now()).also { updated = it }
        }
        recordJobCounts()
        return updated
    }

    private fun recordJobCounts() {
        val counts = jobs.values.groupingBy { it.kind to it.status }.eachCount()
        AgentKind.values().forEach { kind ->
            HeadlessJobStatus.values().forEach { status ->
                telemetry.recordActiveSessions(
                    GatewayActiveSessionsSample(
                        status = status.toTelemetryStatus(),
                        kind = kind.toTelemetryKind(),
                        mode = GatewayModeLabel.HEADLESS,
                        count = counts[kind to status]?.toLong() ?: 0,
                    ),
                )
            }
        }
    }

    private fun recordHeadlessJobEvent(
        kind: AgentKind,
        operation: GatewayOperationLabel,
        outcome: GatewayOutcomeLabel,
        reason: GatewayFailureReasonLabel = GatewayFailureReasonLabel.NONE,
        duration: Duration,
    ) {
        telemetry.recordOperation(
            GatewayOperationTelemetry(
                operation = operation,
                kind = kind.toTelemetryKind(),
                mode = GatewayModeLabel.HEADLESS,
                outcome = outcome,
                reason = reason,
                duration = duration,
            ),
        )
    }

    private fun recordCleanup(
        kind: AgentKind,
        outcome: GatewayOutcomeLabel,
        reason: GatewayFailureReasonLabel,
    ) {
        recordHeadlessJobEvent(
            kind = kind,
            operation = GatewayOperationLabel.STOP,
            outcome = outcome,
            reason = reason,
            duration = Duration.ZERO,
        )
    }

    private fun jobDuration(job: HeadlessJob): Duration {
        val completedAt = job.completedAt ?: Instant.now()
        return Duration.between(job.createdAt, completedAt)
    }

    private fun startFailureReason(ex: Throwable): GatewayFailureReasonLabel =
        when (ex) {
            is IOException -> GatewayFailureReasonLabel.IO_ERROR
            is SecurityException -> GatewayFailureReasonLabel.PERMISSION_DENIED
            else -> GatewayFailureReasonLabel.UNKNOWN
        }

    private fun AgentKind.toTelemetryKind(): GatewayAgentKindLabel = GatewayAgentKindLabel.fromRaw(name)

    private fun HeadlessJobStatus.toTelemetryStatus(): GatewayStatusLabel = GatewayStatusLabel.fromRaw(name)

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
        partialMessages: Boolean = false,
    ): List<String> =
        when (kind) {
            AgentKind.CLAUDE ->
                listOf(
                    props.cli.claude,
                    "-p",
                    "--output-format",
                    "stream-json",
                ) + (if (partialMessages) listOf("--include-partial-messages") else emptyList()) +
                    cliSessionId?.let { listOf("--resume", it) }.orEmpty() +
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

data class HeadlessLaunchRequest(
    val kind: AgentKind,
    val prompt: String,
    val workspacePath: String? = null,
    val cliSessionId: String? = null,
    val timeoutSeconds: Long = HeadlessJobManager.DEFAULT_TIMEOUT_SECONDS,
    val partialMessages: Boolean = false,
)

private data class HeadlessRunContext(
    val id: String,
    val kind: AgentKind,
    val command: List<String>,
    val cwd: File,
    val outputFile: File,
    val timeoutSeconds: Long,
)
