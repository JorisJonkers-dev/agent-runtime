package com.jorisjonkers.personalstack.agentgateway.tmux

import com.jorisjonkers.personalstack.agentgateway.config.GatewayProperties
import com.jorisjonkers.personalstack.agentgateway.observability.GatewayAgentKindLabel
import com.jorisjonkers.personalstack.agentgateway.observability.GatewayFailureReasonLabel
import com.jorisjonkers.personalstack.agentgateway.observability.GatewayOperationLabel
import com.jorisjonkers.personalstack.agentgateway.observability.GatewayOutcomeLabel
import io.micrometer.observation.Observation
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID
import kotlin.io.path.Path

internal class AgentSessionControls(
    private val tmux: TmuxClient,
    private val props: GatewayProperties,
    private val registry: AgentSessionRegistry,
    private val telemetry: AgentSessionTelemetry,
) : AgentSessionControlOperations {
    private val log = LoggerFactory.getLogger(AgentSessionControls::class.java)

    override fun send(
        id: String,
        input: String,
        enter: Boolean,
    ) = withSessionOperation(id, GatewayOperationLabel.INPUT, observed = true) { session ->
        tmux.sendKeys(session.tmuxSession, input, enter = enter)
    }

    override fun stageInput(
        id: String,
        content: String,
        requestedName: String?,
    ): StagedInput =
        withSessionOperation(id, GatewayOperationLabel.INPUT, observed = false) { session ->
            val bytes = content.toByteArray(Charsets.UTF_8)
            require(bytes.isNotEmpty()) { "staged input content is empty" }
            require(bytes.size.toLong() <= props.stagedInputs.maxBytes) {
                "staged input exceeds ${props.stagedInputs.maxBytes} bytes"
            }

            val root = Path(session.cwd).toAbsolutePath().normalize()
            val dir = root.resolve(props.stagedInputs.dirName).normalize()
            require(dir.startsWith(root)) { "staged input directory must stay inside the workspace" }

            Files.createDirectories(dir)
            val safeName = safeFileName(requestedName)
            val fileName = "${timestamp()}-${UUID.randomUUID().toString().take(ID_PREVIEW_CHARS)}-$safeName"
            val target = dir.resolve(fileName).normalize()
            require(target.startsWith(dir)) { "staged input path must stay inside the staging directory" }

            Files.write(target, bytes, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
            log.info("staged {} bytes for agent {} at {}", bytes.size, id, target)
            StagedInput(path = target.toString(), bytes = bytes.size.toLong(), name = safeName)
        }

    override fun capture(
        id: String,
        historyLines: Int,
    ): String =
        withSessionOperation(id, GatewayOperationLabel.REPLAY, observed = false) { session ->
            tmux.capture(session.tmuxSession, historyLines)
        }

    override fun captureWithEscapes(id: String): String =
        withSessionOperation(id, GatewayOperationLabel.REPLAY, observed = false) { session ->
            tmux.captureWithEscapes(session.tmuxSession)
        }

    override fun resize(
        id: String,
        cols: Int,
        rows: Int,
    ) = withSessionOperation(id, GatewayOperationLabel.RESIZE, observed = true) { session ->
        tmux.resize(session.tmuxSession, cols, rows)
    }

    private fun <T> withSessionOperation(
        id: String,
        operation: GatewayOperationLabel,
        observed: Boolean,
        block: (AgentSession) -> T,
    ): T {
        val startedAt = Instant.now()
        var kind = GatewayAgentKindLabel.OTHER
        var outcome = GatewayOutcomeLabel.SUCCESS
        var reason = GatewayFailureReasonLabel.NONE
        var failure: Throwable? = null
        var observation: Observation? = null
        try {
            return runCatching {
                val session = registry.get(id) ?: error("unknown agent: $id")
                kind = with(telemetry) { session.kind.toTelemetryKind() }
                if (observed) observation = telemetry.startSessionObservation(operation, kind)
                block(session)
            }.getOrElse { error ->
                outcome = GatewayOutcomeLabel.FAILURE
                reason = telemetry.failureReasonLabel(error)
                failure = error
                throw error
            }
        } finally {
            telemetry.recordSessionOperation(
                SessionOperationRecord(
                    operation = operation,
                    kind = kind,
                    outcome = outcome,
                    reason = reason,
                    startedAt = startedAt,
                    observed = observed,
                    observation = observation,
                    error = failure,
                ),
            )
        }
    }

    private fun safeFileName(requestedName: String?): String {
        val raw = requestedName?.trim()?.takeIf { it.isNotBlank() } ?: DEFAULT_STAGED_INPUT_NAME
        val leaf = raw.replace('\\', '/').substringAfterLast('/')
        val safe =
            SAFE_NAME_CHARS
                .replace(leaf, "-")
                .trim('.', '-', '_')
                .take(MAX_STAGED_INPUT_NAME_CHARS)
        return safe.takeIf { it.isNotBlank() } ?: DEFAULT_STAGED_INPUT_NAME
    }

    private fun timestamp(): String = STAGED_INPUT_TIMESTAMP.format(Instant.now())

    private companion object {
        const val DEFAULT_STAGED_INPUT_NAME = "input.txt"
        const val ID_PREVIEW_CHARS = 8
        const val MAX_STAGED_INPUT_NAME_CHARS = 80
        val SAFE_NAME_CHARS = Regex("[^A-Za-z0-9._-]+")
        val STAGED_INPUT_TIMESTAMP: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS").withZone(ZoneOffset.UTC)
    }
}
