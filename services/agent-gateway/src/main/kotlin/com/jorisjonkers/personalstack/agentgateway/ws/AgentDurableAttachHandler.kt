package com.jorisjonkers.personalstack.agentgateway.ws

import com.jorisjonkers.personalstack.agentgateway.config.GatewayProperties
import com.jorisjonkers.personalstack.agentgateway.observability.GatewayAgentKindLabel
import com.jorisjonkers.personalstack.agentgateway.observability.GatewayFailureReasonLabel
import com.jorisjonkers.personalstack.agentgateway.observability.GatewayModeLabel
import com.jorisjonkers.personalstack.agentgateway.observability.GatewayOutcomeLabel
import com.jorisjonkers.personalstack.agentgateway.tmux.TranscriptStore
import com.jorisjonkers.personalstack.agentgateway.tmux.TranscriptTailer
import com.jorisjonkers.personalstack.agentgateway.tmux.TranscriptTailerOptions
import io.micrometer.observation.ObservationRegistry
import org.slf4j.LoggerFactory
import org.springframework.web.socket.WebSocketSession
import java.time.Instant
import java.util.concurrent.ConcurrentMap

internal class AgentDurableAttachHandler(
    private val props: GatewayProperties,
    private val transcriptStore: TranscriptStore,
    private val sender: AgentWebSocketSender,
    private val telemetry: AgentAttachTelemetryRecorder,
    private val tailers: ConcurrentMap<String, AutoCloseable>,
    private val observationRegistry: ObservationRegistry,
) {
    private val log = LoggerFactory.getLogger(AgentDurableAttachHandler::class.java)

    fun attach(context: DurableAttachContext) {
        val metadataResult = runCatching { transcriptStore.recoverMetadata(context.stableSessionId) }
        metadataResult.onFailure { failAttach(context, failureReasonLabel(it), "metadata recovery failed") }
        val metadata = metadataResult.getOrNull()
        if (metadata != null) {
            val request = DurableReplayRequest.from(context, metadata.logicalStart, metadata.logicalEnd)
            if (request.malformed) recordInvalidReplayRequest(context)
            if (sendReplayControl(context, request)) {
                val tailer = createTailer(context, request.replayStart)
                tailers[context.session.id] = tailer.delegate
                val replaySendFailure = replayAvailable(context, tailer)
                if (!replaySendFailure && sendReplayComplete(context)) startLiveTailing(context, tailer.delegate)
            }
        }
    }

    private fun sendReplayControl(
        context: DurableAttachContext,
        request: DurableReplayRequest,
    ): Boolean =
        runCatching {
            sender.sendJson(
                context.session,
                mapOf(
                    "control" to request.control,
                    "stableSessionId" to context.stableSessionId,
                    "epoch" to context.epoch,
                    "start" to request.logicalStart,
                    "end" to request.logicalEnd,
                ),
                requireOpen = true,
            )
            if (!request.resume) sender.sendJson(context.session, mapOf("reset" to true), requireOpen = true)
            if (request.logicalStart > 0) {
                sender.sendJson(context.session, mapOf("trim" to request.logicalStart), requireOpen = true)
            }
            sender.sendJson(context.session, mapOf("cursor" to request.replayStart), requireOpen = true)
        }.fold(
            onSuccess = { true },
            onFailure = {
                failAttach(context, failureReasonLabel(it), "websocket send failed")
                false
            },
        )

    private fun createTailer(
        context: DurableAttachContext,
        replayStart: Long,
    ): DurableTranscriptTailer {
        var replaySendFailure = false
        val tailer =
            TranscriptTailer(
                store = transcriptStore,
                stableSessionId = context.stableSessionId,
                startOffset = replayStart,
                onText = { frame ->
                    runCatching {
                        sender.sendJson(
                            context.session,
                            mapOf("output" to frame.output, "off" to frame.off),
                            requireOpen = true,
                        )
                    }.onFailure { replaySendFailure = true }
                        .getOrThrow()
                },
                options =
                    TranscriptTailerOptions(
                        intervalMs = props.tmux.tailIntervalMs,
                        onTrim = {
                            sender.sendJson(
                                context.session,
                                mapOf("trim" to it, "cursor" to it),
                                requireOpen = true,
                            )
                        },
                        observationRegistry = observationRegistry,
                    ),
            )
        return DurableTranscriptTailer(tailer) { replaySendFailure }
    }

    private fun replayAvailable(
        context: DurableAttachContext,
        tailer: DurableTranscriptTailer,
    ): Boolean {
        val replay = tailer.delegate.replayAvailable()
        val sendFailed = tailer.replaySendFailed()
        val replayReason =
            if (sendFailed) {
                GatewayFailureReasonLabel.IO_ERROR
            } else {
                failureReasonLabel(replay.failureReason)
            }
        telemetry.recordReplay(replay.bytes, replay.success, replayReason)
        val sendFailure =
            if (replay.success) {
                recordReplaySuccess(context)
                false
            } else {
                recordReplayFailure(context, replay.bytes, replay.frames, replay.failureReason, replayReason)
                if (sendFailed) failAttach(context, replayReason, "websocket send failed")
                sendFailed
            }
        return sendFailure
    }

    private fun recordReplaySuccess(context: DurableAttachContext) {
        telemetry.recordReplayOperation(
            context.kind,
            context.requestedMode,
            GatewayOutcomeLabel.SUCCESS,
            GatewayFailureReasonLabel.NONE,
        )
    }

    private fun recordReplayFailure(
        context: DurableAttachContext,
        bytes: Long,
        frames: Long,
        failureReason: String?,
        replayReason: GatewayFailureReasonLabel,
    ) {
        log.warn(
            "replay of transcript {} failed before live tailing after {} bytes and {} frames: {}",
            context.stableSessionId,
            bytes,
            frames,
            failureReason,
        )
        telemetry.recordReplayFailure(bytes, replayReason)
        telemetry.recordReplayOperation(
            context.kind,
            context.requestedMode,
            GatewayOutcomeLabel.FAILURE,
            replayReason,
        )
    }

    private fun sendReplayComplete(context: DurableAttachContext): Boolean {
        val metadataResult = runCatching { transcriptStore.recoverMetadata(context.stableSessionId) }
        metadataResult.onFailure { failAttach(context, failureReasonLabel(it), "metadata recovery failed") }
        val replayEnd = metadataResult.getOrNull()?.logicalEnd
        return replayEnd != null && sendReplayComplete(context, replayEnd)
    }

    private fun sendReplayComplete(
        context: DurableAttachContext,
        replayEnd: Long,
    ): Boolean =
        runCatching {
            sender.sendJson(
                context.session,
                mapOf("control" to "REPLAY_COMPLETE", "cursor" to replayEnd),
                requireOpen = true,
            )
        }.fold(
            onSuccess = { true },
            onFailure = {
                failAttach(context, failureReasonLabel(it), "websocket send failed")
                false
            },
        )

    private fun startLiveTailing(
        context: DurableAttachContext,
        tailer: TranscriptTailer,
    ) {
        val start = tailer.start()
        val startReason =
            if (start.success) {
                GatewayFailureReasonLabel.NONE
            } else {
                failureReasonLabel(start.failureReason)
            }
        val outcome = if (start.success) GatewayOutcomeLabel.SUCCESS else GatewayOutcomeLabel.FAILURE
        telemetry.recordTailerStartup(context.kind, context.requestedMode, outcome, startReason)
        telemetry.recordAttachTerminal(context.kind, context.requestedMode, outcome, startReason, context.startedAt)
        if (!start.success) sender.closeServerError(context.session, "tailer startup failed")
    }

    private fun recordInvalidReplayRequest(context: DurableAttachContext) {
        telemetry.recordAttachFailure(
            kind = context.kind,
            mode = context.requestedMode,
            reason = GatewayFailureReasonLabel.INVALID_REQUEST,
        )
    }

    private fun failAttach(
        context: DurableAttachContext,
        reason: GatewayFailureReasonLabel,
        closeReason: String,
    ) {
        telemetry.recordAttachTerminal(
            context.kind,
            context.requestedMode,
            GatewayOutcomeLabel.FAILURE,
            reason,
            context.startedAt,
        )
        sender.closeServerError(context.session, closeReason)
    }
}

private data class DurableTranscriptTailer(
    val delegate: TranscriptTailer,
    val replaySendFailed: () -> Boolean,
)

internal data class DurableAttachContext(
    val session: WebSocketSession,
    val stableSessionId: String,
    val epoch: Long,
    val kind: GatewayAgentKindLabel,
    val requestedMode: GatewayModeLabel,
    val query: Map<String, String>,
    val startedAt: Instant,
)

private data class DurableReplayRequest(
    val logicalStart: Long,
    val logicalEnd: Long,
    val replayStart: Long,
    val resume: Boolean,
    val malformed: Boolean,
) {
    val control: String = if (resume) "RESUME" else "SNAPSHOT"

    companion object {
        fun from(
            context: DurableAttachContext,
            logicalStart: Long,
            logicalEnd: Long,
        ): DurableReplayRequest {
            val requestedOffset = AgentAttachQuery.parseOffset(context.query)
            val requestedEpoch = AgentAttachQuery.parseEpoch(context.query)
            val mode = context.query["mode"]?.uppercase()
            val resumeRequest = DurableReplayResumeRequest(requestedEpoch, requestedOffset, mode)
            val resumeWindow = DurableReplayResumeWindow(context.epoch, logicalStart, logicalEnd)
            val resume = DurableReplayResumeDecision(resumeRequest, resumeWindow).canResume
            val coldStart = maxOf(logicalStart, logicalEnd - AgentAttachLimits.MAX_COLD_REPLAY_BYTES)
            return DurableReplayRequest(
                logicalStart = logicalStart,
                logicalEnd = logicalEnd,
                replayStart = if (resume) requestedOffset.value ?: coldStart else coldStart,
                resume = resume,
                malformed = requestedOffset.malformed || requestedEpoch.malformed,
            )
        }
    }
}

private data class DurableReplayResumeRequest(
    val requestedEpoch: ParsedLong,
    val requestedOffset: ParsedLong,
    val mode: String?,
)

private data class DurableReplayResumeWindow(
    val epoch: Long,
    val logicalStart: Long,
    val logicalEnd: Long,
)

private data class DurableReplayResumeDecision(
    val request: DurableReplayResumeRequest,
    val window: DurableReplayResumeWindow,
) {
    val canResume: Boolean =
        (request.mode == "RESUME" || request.mode == null) &&
            request.requestedEpoch.value == window.epoch &&
            request.requestedOffset.value != null &&
            request.requestedOffset.value in window.logicalStart..window.logicalEnd &&
            window.logicalEnd - request.requestedOffset.value <= AgentAttachLimits.MAX_RESUME_REPLAY_BYTES
}
