package com.jorisjonkers.personalstack.agentgateway.headless

import com.jorisjonkers.personalstack.agentgateway.config.GatewayProperties
import com.jorisjonkers.personalstack.agentgateway.observability.AgentGatewayTelemetry
import com.jorisjonkers.personalstack.agentgateway.observability.GatewayOperationLabel
import com.jorisjonkers.personalstack.agentgateway.observability.GatewayOutcomeLabel
import com.jorisjonkers.personalstack.agentgateway.tmux.AgentKind
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.DisposableBean
import org.springframework.beans.factory.InitializingBean
import org.springframework.stereotype.Component
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * Async registry for one-shot (headless) agent runs. Each job launches
 * the CLI as a child process with stdin closed, streams stdout+stderr
 * to a JSONL capture file in the gateway state dir, and updates status
 * when the process exits or times out.
 *
 * Concurrency: one virtual thread per job; job transitions are serialized
 * by the registry so concurrent status reads are safe.
 * Cancellation kills the OS process and marks the job CANCELLED.
 *
 * Durability: job metadata is persisted as a JSON sidecar file
 * (`headless-<id>.json`) next to the output JSONL file in the state dir.
 * On startup the manager reloads any completed sidecar files so callers
 * can still read the final status and output of jobs that finished before
 * the current process started. Jobs that were RUNNING at the time of a
 * previous restart are re-surfaced as FAILED (the process died with them).
 *
 * KB hooks: `KB_AUTO_MCP_DISABLED=1` is injected into every headless
 * process environment by default. Pass `enableKbHooks = true` in
 * [HeadlessLaunchRequest] to opt a specific run back in (council workers
 * that explicitly want KB recall/capture may do so).
 */
@Component
class HeadlessJobManager(
    private val props: GatewayProperties,
    telemetry: AgentGatewayTelemetry = AgentGatewayTelemetry.NOOP,
    processFactory: ProcessFactory = DefaultProcessFactory,
) : DisposableBean,
    InitializingBean {
    private val log = LoggerFactory.getLogger(HeadlessJobManager::class.java)
    private val stateDir: Path = Path.of(props.tmux.stateDir)
    private val jobTelemetry = HeadlessJobTelemetry(telemetry)
    private val registry = HeadlessJobRegistry(stateDir, jobTelemetry)
    private val lifecycle = HeadlessProcessLifecycle(registry, jobTelemetry, processFactory)

    /** Reload durable job state from the state directory at startup. */
    override fun afterPropertiesSet() {
        registry.reload()
    }

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
        Files.createDirectories(stateDir)
        val outputFile = stateDir.resolve("headless-$id.jsonl")
        Files.createFile(outputFile)
        val command = headlessCommandFor(request.kind, request.prompt, request.cliSessionId, request.partialMessages)
        val job = registry.register(
            HeadlessJob(
                id = id,
                kind = request.kind,
                status = HeadlessJobStatus.RUNNING,
                outputFile = outputFile,
                createdAt = Instant.now(),
            ),
        )
        jobTelemetry.recordEvent(
            kind = request.kind,
            operation = GatewayOperationLabel.SPAWN,
            outcome = GatewayOutcomeLabel.SUCCESS,
            duration = Duration.between(job.createdAt, Instant.now()),
        )
        lifecycle.submit(
            HeadlessRunContext(
                id = id,
                kind = request.kind,
                command = command,
                cwd = cwd,
                outputFile = outputFile.toFile(),
                timeoutSeconds = request.timeoutSeconds,
                enableKbHooks = request.enableKbHooks,
            ),
        )
        log.info("launched headless {} job {} in {} (kbHooks={})", request.kind, id, cwd, request.enableKbHooks)
        return job
    }

    fun get(id: String): HeadlessJob? = registry.get(id)

    fun list(): List<HeadlessJob> = registry.list()

    fun cancel(id: String): Boolean = lifecycle.cancel(id)

    fun readOutput(
        id: String,
        maxChars: Int = MAX_OUTPUT_CHARS,
    ): String = registry.readOutput(id, maxChars)

    override fun destroy() {
        lifecycle.destroy()
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
            enableKbHooks: Boolean,
        ): Process
    }

    companion object {
        const val DEFAULT_TIMEOUT_SECONDS = 600L
        const val MAX_OUTPUT_CHARS = 65_536

        /** Environment variable that suppresses auto-KB recall/capture hooks in agent-kit. */
        const val KB_AUTO_MCP_DISABLED_KEY = "KB_AUTO_MCP_DISABLED"
        const val KB_AUTO_MCP_DISABLED_VALUE = "1"
        val DefaultProcessFactory =
            ProcessFactory { command, cwd, enableKbHooks ->
                ProcessBuilder(command)
                    .directory(cwd)
                    .redirectErrorStream(true)
                    .also { pb ->
                        if (!enableKbHooks) {
                            pb.environment()[KB_AUTO_MCP_DISABLED_KEY] = KB_AUTO_MCP_DISABLED_VALUE
                        }
                    }.start()
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
    /**
     * When false (the default) the process environment includes
     * `KB_AUTO_MCP_DISABLED=1` so headless/council workers do not fire
     * auto-KB recall or capture hooks. Set to true only for runs that
     * explicitly need KB hook access.
     */
    val enableKbHooks: Boolean = false,
)
