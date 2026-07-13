package com.jorisjonkers.personalstack.agentgateway.tmux

import com.jorisjonkers.personalstack.agentgateway.config.GatewayProperties
import com.jorisjonkers.personalstack.agentgateway.observability.GatewayFailureReasonLabel
import com.jorisjonkers.personalstack.agentgateway.observability.GatewayOperationLabel
import com.jorisjonkers.personalstack.agentgateway.observability.GatewayOutcomeLabel
import org.slf4j.LoggerFactory
import java.nio.file.Path
import java.time.Instant
import java.util.UUID

internal class AgentSessionSpawnWorkflow(
    private val tmux: TmuxClient,
    private val props: GatewayProperties,
    private val transcriptStore: TranscriptStore,
    private val registry: AgentSessionRegistry,
    private val telemetry: AgentSessionTelemetry,
    claudeTranscriptLocator: ClaudeTranscriptLocator,
) : AgentSessionSpawnOperations {
    private val log = LoggerFactory.getLogger(AgentSessionSpawnWorkflow::class.java)
    private val codexHomes = CodexSessionHomes(props)
    private val commandFactory = AgentCommandFactory(props, claudeTranscriptLocator)

    override fun spawn(
        kind: AgentKind,
        workspacePath: String?,
        stableSessionId: String?,
        epoch: Long?,
        continuation: AgentContinuation?,
    ): AgentSession =
        spawn(
            AgentSpawnRequest(
                kind = kind,
                workspacePath = workspacePath,
                stableSessionId = stableSessionId,
                epoch = epoch,
                continuation = continuation,
            ),
        )

    override fun spawn(
        kind: AgentKind,
        workspacePath: String?,
        resumeCliSessionId: String,
    ): AgentSession =
        spawn(
            AgentSpawnRequest(
                kind = kind,
                workspacePath = workspacePath,
                resumeCliSessionId = resumeCliSessionId,
            ),
        )

    override fun spawn(request: AgentSpawnRequest): AgentSession {
        val startedAt = Instant.now()
        val telemetryKind = with(telemetry) { request.kind.toTelemetryKind() }
        var outcome = GatewayOutcomeLabel.SUCCESS
        var reason = GatewayFailureReasonLabel.NONE
        var failure: Throwable? = null
        val observation = telemetry.startSessionObservation(GatewayOperationLabel.SPAWN, telemetryKind)
        try {
            return runCatching { createSession(request) }
                .getOrElse { error ->
                    outcome = GatewayOutcomeLabel.FAILURE
                    reason = telemetry.failureReasonLabel(error)
                    failure = error
                    throw error
                }
        } finally {
            telemetry.recordSessionOperation(
                SessionOperationRecord(
                    operation = GatewayOperationLabel.SPAWN,
                    kind = telemetryKind,
                    outcome = outcome,
                    reason = reason,
                    startedAt = startedAt,
                    observation = observation,
                    error = failure,
                ),
            )
        }
    }

    private fun createSession(request: AgentSpawnRequest): AgentSession {
        val id = UUID.randomUUID().toString().substring(0, ID_PREVIEW_CHARS)
        val tmuxSession = "agent-$id"
        val requestedCwd = request.workspacePath ?: props.workspaceRoot
        val stableSessionId =
            request.stableSessionId?.let(transcriptStore::validateStableSessionId) ?: UUID.randomUUID().toString()
        val epoch = request.epoch ?: 1
        require(epoch > 0) { "epoch must be positive" }
        val lease = transcriptStore.acquireLease(stableSessionId, tmuxSession, epoch)
        val logFile = openTranscript(request, stableSessionId, epoch, lease)
        val codexHome = if (request.kind == AgentKind.CODEX) codexHomes.homeFor(stableSessionId) else null
        codexHome?.let(codexHomes::prepare)
        val launch =
            commandFactory.commandAndSessionIdFor(
                kind = request.kind,
                cwd = requestedCwd,
                resumeCliSessionId = request.resumeCliSessionId,
                codexHome = codexHome,
            )
        startTmux(tmuxSession, launch, logFile, lease)
        val session = sessionFrom(id, request, tmuxSession, launch, logFile, lease, stableSessionId, epoch, codexHome)
        registry.put(session)
        telemetry.recordActiveSessionCounts()
        log.info(
            "spawned {} agent {} ({}) in {} cliSessionId={} stableSessionId={} epoch={}",
            request.kind,
            id,
            tmuxSession,
            launch.cwd,
            session.cliSessionId,
            stableSessionId,
            epoch,
        )
        return session
    }

    private fun openTranscript(
        request: AgentSpawnRequest,
        stableSessionId: String,
        epoch: Long,
        lease: TranscriptLease,
    ): Path {
        var opened = false
        try {
            transcriptStore.open(stableSessionId, epoch)
            if (epoch > 1 || request.continuation != null) {
                transcriptStore.appendContinuationDelimiter(stableSessionId, epoch, request.continuation)
            }
            return transcriptStore.activeSegmentPath(stableSessionId).also { opened = true }
        } finally {
            if (!opened) transcriptStore.releaseLease(lease)
        }
    }

    private fun startTmux(
        tmuxSession: String,
        launch: AgentCommand,
        logFile: Path,
        lease: TranscriptLease,
    ) {
        var started = false
        try {
            tmux.newSession(tmuxSession, launch.command, launch.cwd)
            tmux.startPipeToFile(tmuxSession, logFile)
            started = true
        } finally {
            if (!started) transcriptStore.releaseLease(lease)
        }
    }

    private fun sessionFrom(
        id: String,
        request: AgentSpawnRequest,
        tmuxSession: String,
        launch: AgentCommand,
        logFile: Path,
        lease: TranscriptLease,
        stableSessionId: String,
        epoch: Long,
        codexHome: Path?,
    ): AgentSession {
        val cliSessionId =
            launch.cliSessionId
                ?: codexHome?.takeIf { request.kind == AgentKind.CODEX }?.let(codexHomes::captureSessionId)
        return AgentSession(
            id = id,
            kind = request.kind,
            tmuxSession = tmuxSession,
            logFile = logFile,
            cwd = launch.cwd,
            createdAt = Instant.now(),
            cliSessionId = cliSessionId,
            stableSessionId = stableSessionId,
            epoch = epoch,
            continuation = request.continuation,
            transcriptFile = logFile,
            transcriptLease = lease,
        )
    }

    private companion object {
        const val ID_PREVIEW_CHARS = 8
    }
}
