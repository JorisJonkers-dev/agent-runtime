package com.jorisjonkers.personalstack.agentgateway.tmux

import com.jorisjonkers.personalstack.agentgateway.config.GatewayProperties
import com.jorisjonkers.personalstack.agentgateway.observability.GatewayFailureReasonLabel
import com.jorisjonkers.personalstack.agentgateway.observability.GatewayOperationLabel
import com.jorisjonkers.personalstack.agentgateway.observability.GatewayOutcomeLabel
import org.slf4j.LoggerFactory
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Instant

internal class AgentTranscriptMaintenance(
    private val tmux: TmuxClient,
    private val props: GatewayProperties,
    private val transcriptStore: TranscriptStore,
    private val registry: AgentSessionRegistry,
    private val telemetry: AgentSessionTelemetry,
) {
    private val log = LoggerFactory.getLogger(AgentTranscriptMaintenance::class.java)

    fun maintain() {
        registry.values().forEach(::maintainSession)
    }

    private fun maintainSession(session: AgentSession) {
        val startedAt = Instant.now()
        runCatching {
            if (session.stableSessionId != null) {
                maintainDurableSession(session, startedAt)
            } else {
                maintainLegacyLog(session, startedAt)
            }
        }.onFailure {
            recordMaintenance(session, startedAt, GatewayOutcomeLabel.FAILURE, it)
            log.warn("trim of {} failed: {}", session.logFile, it.message)
        }
    }

    private fun maintainDurableSession(
        session: AgentSession,
        startedAt: Instant,
    ) {
        val stableSessionId = requireNotNull(session.stableSessionId)
        val beforeTrim = transcriptStore.recoverMetadata(stableSessionId).logicalStart
        val current = renewLease(session)
        transcriptStore.rotateIfNeeded(stableSessionId)
        rotatePipeIfNeeded(current, transcriptStore.activeSegmentPath(stableSessionId))
        val trimmed = transcriptStore.trimIfNeeded(stableSessionId)
        if (trimmed.logicalStart > beforeTrim) {
            recordMaintenance(current, startedAt, GatewayOutcomeLabel.SUCCESS)
        }
    }

    private fun renewLease(session: AgentSession): AgentSession {
        val current =
            session.transcriptLease
                ?.let(transcriptStore::renewLease)
                ?.let { session.copy(transcriptLease = it) }
                ?: session
        if (current !== session) registry.update(current)
        return current
    }

    private fun rotatePipeIfNeeded(
        session: AgentSession,
        active: Path,
    ) {
        if (active == session.logFile) return
        tmux.startPipeToFile(session.tmuxSession, active)
        registry.update(session.copy(logFile = active, transcriptFile = active))
        log.info("rotated agent {} transcript pipe to {}", session.id, active)
    }

    private fun maintainLegacyLog(
        session: AgentSession,
        startedAt: Instant,
    ) {
        val file = session.logFile
        if (!Files.exists(file) || Files.size(file) <= props.tmux.logCapBytes) return
        FileChannel.open(file, StandardOpenOption.WRITE).use { it.truncate(0) }
        log.info("trimmed agent {} log past {} bytes", session.id, props.tmux.logCapBytes)
        recordMaintenance(session, startedAt, GatewayOutcomeLabel.SUCCESS)
    }

    private fun recordMaintenance(
        session: AgentSession,
        startedAt: Instant,
        outcome: GatewayOutcomeLabel,
        error: Throwable? = null,
    ) {
        telemetry.recordSessionOperation(
            SessionOperationRecord(
                operation = GatewayOperationLabel.REPLAY,
                kind = with(telemetry) { session.kind.toTelemetryKind() },
                outcome = outcome,
                reason = error?.let(telemetry::failureReasonLabel) ?: GatewayFailureReasonLabel.NONE,
                startedAt = startedAt,
                observed = false,
                error = error,
            ),
        )
    }
}
