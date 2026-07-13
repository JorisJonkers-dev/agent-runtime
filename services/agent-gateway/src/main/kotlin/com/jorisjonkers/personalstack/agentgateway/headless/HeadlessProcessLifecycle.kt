package com.jorisjonkers.personalstack.agentgateway.headless

import com.jorisjonkers.personalstack.agentgateway.observability.GatewayFailureReasonLabel
import com.jorisjonkers.personalstack.agentgateway.observability.GatewayOperationLabel
import com.jorisjonkers.personalstack.agentgateway.observability.GatewayOutcomeLabel
import com.jorisjonkers.personalstack.agentgateway.tmux.AgentKind
import org.slf4j.LoggerFactory
import java.io.File
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

internal class HeadlessProcessLifecycle(
    private val registry: HeadlessJobRegistry,
    private val telemetry: HeadlessJobTelemetry,
    private val processFactory: HeadlessJobManager.ProcessFactory,
) {
    private val log = LoggerFactory.getLogger(HeadlessProcessLifecycle::class.java)
    private val processes = ConcurrentHashMap<String, Process>()
    private val executor: ExecutorService = Executors.newVirtualThreadPerTaskExecutor()

    fun submit(context: HeadlessRunContext) {
        executor.submit { runJob(context) }
    }

    fun cancel(id: String): Boolean {
        val process =
            processes.remove(id)
                ?: return registry.get(id)?.let { job ->
                    telemetry.recordEvent(
                        kind = job.kind,
                        operation = GatewayOperationLabel.STOP,
                        outcome = GatewayOutcomeLabel.SKIPPED,
                        duration = Duration.ZERO,
                    )
                    true
                } ?: false
        process.destroyForcibly()
        val cancelled = registry.markStatus(id, HeadlessJobStatus.CANCELLED)
        cancelled?.let {
            telemetry.recordEvent(
                kind = it.kind,
                operation = GatewayOperationLabel.STOP,
                outcome = GatewayOutcomeLabel.CANCELLED,
                reason = GatewayFailureReasonLabel.CANCELLED,
                duration = registry.duration(it),
            )
        }
        log.info("cancelled headless job {}", id)
        return true
    }

    fun destroy() {
        executor.shutdownNow()
        runCatching { executor.awaitTermination(SHUTDOWN_GRACE_SECONDS, TimeUnit.SECONDS) }
    }

    private fun runJob(context: HeadlessRunContext) {
        val process =
            startProcess(context)
                ?: run {
                    telemetry.recordCleanup(
                        context.kind,
                        GatewayOutcomeLabel.SKIPPED,
                        GatewayFailureReasonLabel.UNKNOWN,
                    )
                    return
                }
        processes[context.id] = process
        try {
            awaitAndCapture(context, process)
        } catch (ex: InterruptedException) {
            process.destroyForcibly()
            registry.markStatus(context.id, HeadlessJobStatus.CANCELLED)?.let {
                telemetry.recordEvent(
                    kind = it.kind,
                    operation = GatewayOperationLabel.HEADLESS_JOB,
                    outcome = GatewayOutcomeLabel.CANCELLED,
                    reason = GatewayFailureReasonLabel.CANCELLED,
                    duration = registry.duration(it),
                )
            }
        } finally {
            val removed = processes.remove(context.id)
            val reason =
                if (registry.get(context.id)?.status == HeadlessJobStatus.CANCELLED) {
                    GatewayFailureReasonLabel.CANCELLED
                } else {
                    GatewayFailureReasonLabel.NONE
                }
            val outcome = if (removed == null) GatewayOutcomeLabel.SKIPPED else GatewayOutcomeLabel.SUCCESS
            telemetry.recordCleanup(context.kind, outcome, reason)
        }
    }

    private fun startProcess(context: HeadlessRunContext): Process? =
        runCatching {
            processFactory.start(context.command, context.cwd, context.enableKbHooks)
        }.getOrElse { ex ->
            log.error("headless job {} failed to start: {}", context.id, ex.message)
            registry.markStatus(context.id, HeadlessJobStatus.FAILED)?.let {
                telemetry.recordEvent(
                    kind = context.kind,
                    operation = GatewayOperationLabel.SPAWN,
                    outcome = GatewayOutcomeLabel.FAILURE,
                    reason = telemetry.startFailureReason(ex),
                    duration = registry.duration(it),
                )
            }
            null
        }

    private fun awaitAndCapture(
        context: HeadlessRunContext,
        process: Process,
    ) {
        val gobbler = Thread.ofVirtual().start { process.inputStream.copyTo(context.outputFile.outputStream()) }
        val finished = process.waitFor(context.timeoutSeconds, TimeUnit.SECONDS)
        gobbler.join(GOBBLER_JOIN_MS)
        if (!finished) {
            process.destroyForcibly()
            registry
                .updateUnlessCancelled(context.id, HeadlessJobStatus.FAILED, TIMEOUT_EXIT_CODE)
                ?.takeUnless { it.status == HeadlessJobStatus.CANCELLED }
                ?.let {
                    telemetry.recordEvent(
                        kind = it.kind,
                        operation = GatewayOperationLabel.HEADLESS_JOB,
                        outcome = GatewayOutcomeLabel.FAILURE,
                        reason = GatewayFailureReasonLabel.TIMEOUT,
                        duration = registry.duration(it),
                    )
                }
            log.warn("headless job {} timed out after {}s", context.id, context.timeoutSeconds)
        } else {
            val exitCode = process.exitValue()
            val status = if (exitCode == 0) HeadlessJobStatus.COMPLETED else HeadlessJobStatus.FAILED
            registry
                .updateUnlessCancelled(context.id, status, exitCode)
                ?.takeUnless { it.status == HeadlessJobStatus.CANCELLED }
                ?.let {
                    val outcome =
                        if (status == HeadlessJobStatus.COMPLETED) {
                            GatewayOutcomeLabel.SUCCESS
                        } else {
                            GatewayOutcomeLabel.FAILURE
                        }
                    val reason =
                        if (status == HeadlessJobStatus.COMPLETED) {
                            GatewayFailureReasonLabel.NONE
                        } else {
                            GatewayFailureReasonLabel.PROCESS_EXITED
                        }
                    telemetry.recordEvent(
                        kind = it.kind,
                        operation = GatewayOperationLabel.HEADLESS_JOB,
                        outcome = outcome,
                        reason = reason,
                        duration = registry.duration(it),
                    )
                }
            log.info("headless job {} finished status={} exitCode={}", context.id, status, exitCode)
        }
    }

    private companion object {
        private const val TIMEOUT_EXIT_CODE = -1
        private const val GOBBLER_JOIN_MS = 2_000L
        private const val SHUTDOWN_GRACE_SECONDS = 5L
    }
}

internal data class HeadlessRunContext(
    val id: String,
    val kind: AgentKind,
    val command: List<String>,
    val cwd: File,
    val outputFile: File,
    val timeoutSeconds: Long,
    val enableKbHooks: Boolean = false,
)
