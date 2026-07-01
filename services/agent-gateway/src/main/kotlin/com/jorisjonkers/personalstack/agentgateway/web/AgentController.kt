@file:Suppress("LongMethod", "ThrowsCount", "TooGenericExceptionCaught", "TooManyFunctions")

package com.jorisjonkers.personalstack.agentgateway.web

import com.jorisjonkers.personalstack.agentgateway.observability.AgentGatewayTelemetry
import com.jorisjonkers.personalstack.agentgateway.observability.GatewayAgentKindLabel
import com.jorisjonkers.personalstack.agentgateway.observability.GatewayFailureReasonLabel
import com.jorisjonkers.personalstack.agentgateway.observability.GatewayModeLabel
import com.jorisjonkers.personalstack.agentgateway.observability.GatewayOperationLabel
import com.jorisjonkers.personalstack.agentgateway.observability.GatewayOperationTelemetry
import com.jorisjonkers.personalstack.agentgateway.observability.GatewayOutcomeLabel
import com.jorisjonkers.personalstack.agentgateway.tmux.AgentContinuation
import com.jorisjonkers.personalstack.agentgateway.tmux.AgentKind
import com.jorisjonkers.personalstack.agentgateway.tmux.AgentSession
import com.jorisjonkers.personalstack.agentgateway.tmux.AgentSessionManager
import com.jorisjonkers.personalstack.agentgateway.tmux.AgentSpawnRequest
import com.jorisjonkers.personalstack.agentgateway.web.dto.AgentResponse
import com.jorisjonkers.personalstack.agentgateway.web.dto.ContinuationMetadata
import com.jorisjonkers.personalstack.agentgateway.web.dto.SendInputRequest
import com.jorisjonkers.personalstack.agentgateway.web.dto.SpawnAgentRequest
import com.jorisjonkers.personalstack.agentgateway.web.dto.StageInputRequest
import com.jorisjonkers.personalstack.agentgateway.web.dto.StagedInputResponse
import io.micrometer.observation.Observation
import io.micrometer.observation.ObservationRegistry
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.Duration
import java.time.Instant

@RestController
@RequestMapping("/agents")
class AgentController(
    private val sessions: AgentSessionManager,
    private val telemetry: AgentGatewayTelemetry = AgentGatewayTelemetry.NOOP,
    private val observationRegistry: ObservationRegistry = ObservationRegistry.NOOP,
) {
    @GetMapping
    fun list(): List<AgentResponse> =
        recordRestOperation(GatewayOperationLabel.UNKNOWN) {
            sessions.list().map(::toResponse)
        }

    @PostMapping
    fun spawn(
        @RequestBody req: SpawnAgentRequest,
    ): ResponseEntity<AgentResponse> =
        recordRestOperation(GatewayOperationLabel.SPAWN, req.kind.toTelemetryKind()) {
            val session =
                sessions.spawn(
                    AgentSpawnRequest(
                        kind = req.kind,
                        workspacePath = req.workspacePath,
                        stableSessionId = req.stableSessionId,
                        epoch = req.epoch,
                        continuation = req.continuation?.toDomain(),
                        resumeCliSessionId = req.resumeCliSessionId,
                    ),
                )
            ResponseEntity.status(HttpStatus.CREATED).body(toResponse(session))
        }

    @DeleteMapping("/transcripts/{stableSessionId}")
    fun cleanupTranscript(
        @PathVariable stableSessionId: String,
    ): ResponseEntity<Unit> =
        recordRestOperation(GatewayOperationLabel.REPLAY) {
            val ok = sessions.cleanupTranscript(stableSessionId)
            if (ok) ResponseEntity.noContent().build() else ResponseEntity.notFound().build()
        }

    @GetMapping("/{id}")
    fun get(
        @PathVariable id: String,
    ): ResponseEntity<AgentResponse> =
        recordRestOperation(GatewayOperationLabel.UNKNOWN) {
            // Only the single-agent lookup carries idleMillis — it's the call
            // the control plane polls to decide whether a runner is quiet
            // enough to recycle. The list/create paths skip the extra stat.
            sessions
                .get(id)
                ?.let { ResponseEntity.ok(toResponse(it).copy(idleMillis = sessions.idleMillis(id))) }
                ?: ResponseEntity.notFound().build()
        }

    @PostMapping("/{id}/send")
    fun send(
        @PathVariable id: String,
        @RequestBody req: SendInputRequest,
    ): ResponseEntity<Unit> =
        recordRestOperation(GatewayOperationLabel.INPUT) {
            sessions.send(id, req.input, req.enter)
            ResponseEntity.accepted().build()
        }

    @PostMapping("/{id}/staged-inputs")
    fun stageInput(
        @PathVariable id: String,
        @RequestBody req: StageInputRequest,
    ): ResponseEntity<StagedInputResponse> =
        recordRestOperation(GatewayOperationLabel.INPUT) {
            val staged = sessions.stageInput(id, req.content, req.name)
            ResponseEntity
                .status(HttpStatus.CREATED)
                .body(StagedInputResponse(path = staged.path, bytes = staged.bytes, name = staged.name))
        }

    @GetMapping("/{id}/capture")
    fun capture(
        @PathVariable id: String,
    ): ResponseEntity<Map<String, String>> =
        recordRestOperation(GatewayOperationLabel.REPLAY) {
            val text = sessions.capture(id)
            ResponseEntity.ok(mapOf("text" to text))
        }

    @DeleteMapping("/{id}")
    fun stop(
        @PathVariable id: String,
    ): ResponseEntity<Unit> =
        recordRestOperation(GatewayOperationLabel.STOP) {
            val ok = sessions.stop(id)
            if (ok) ResponseEntity.noContent().build() else ResponseEntity.notFound().build()
        }

    private fun <T> recordRestOperation(
        operation: GatewayOperationLabel,
        kind: GatewayAgentKindLabel = GatewayAgentKindLabel.OTHER,
        block: () -> T,
    ): T {
        val startedAt = Instant.now()
        var terminal = RestTerminalOutcome.SUCCESS
        var failure: Throwable? = null
        val observation =
            Observation
                .start(REST_OBSERVATION_NAME, observationRegistry)
                .lowCardinalityKeyValue("operation", operation.label)
                .lowCardinalityKeyValue("kind", kind.label)
                .lowCardinalityKeyValue("mode", GatewayModeLabel.LIVE.label)
        try {
            val result = block()
            terminal = terminalOutcome(result)
            return result
        } catch (e: IllegalArgumentException) {
            terminal = RestTerminalOutcome.MALFORMED_INPUT
            failure = e
            throw e
        } catch (e: IllegalStateException) {
            terminal = RestTerminalOutcome.NOT_FOUND
            failure = e
            throw e
        } catch (e: Exception) {
            terminal = RestTerminalOutcome.FAILURE
            failure = e
            throw e
        } finally {
            observation
                .lowCardinalityKeyValue("outcome", terminal.label)
                .lowCardinalityKeyValue("reason", terminal.reason.label)
            failure?.let(observation::error)
            observation.stop()
            telemetry.recordOperation(
                GatewayOperationTelemetry(
                    operation = operation,
                    kind = kind,
                    mode = GatewayModeLabel.LIVE,
                    outcome = terminal.outcome,
                    reason = terminal.reason,
                    duration = Duration.between(startedAt, Instant.now()),
                ),
            )
        }
    }

    private fun terminalOutcome(result: Any?): RestTerminalOutcome {
        val status = (result as? ResponseEntity<*>)?.statusCode ?: return RestTerminalOutcome.SUCCESS
        return when {
            status == HttpStatus.ACCEPTED -> RestTerminalOutcome.ACCEPTED
            status == HttpStatus.NO_CONTENT -> RestTerminalOutcome.NO_CONTENT
            status == HttpStatus.NOT_FOUND -> RestTerminalOutcome.NOT_FOUND
            status.is4xxClientError -> RestTerminalOutcome.MALFORMED_INPUT
            status.is5xxServerError -> RestTerminalOutcome.FAILURE
            else -> RestTerminalOutcome.SUCCESS
        }
    }

    private enum class RestTerminalOutcome(
        val label: String,
        val outcome: GatewayOutcomeLabel,
        val reason: GatewayFailureReasonLabel,
    ) {
        SUCCESS("success", GatewayOutcomeLabel.SUCCESS, GatewayFailureReasonLabel.NONE),
        ACCEPTED("accepted", GatewayOutcomeLabel.SUCCESS, GatewayFailureReasonLabel.NONE),
        NO_CONTENT("no_content", GatewayOutcomeLabel.SUCCESS, GatewayFailureReasonLabel.NONE),
        NOT_FOUND("not_found", GatewayOutcomeLabel.FAILURE, GatewayFailureReasonLabel.NOT_FOUND),
        MALFORMED_INPUT("malformed_input", GatewayOutcomeLabel.FAILURE, GatewayFailureReasonLabel.INVALID_REQUEST),
        FAILURE("failure", GatewayOutcomeLabel.FAILURE, GatewayFailureReasonLabel.UNKNOWN),
    }

    private fun AgentKind.toTelemetryKind(): GatewayAgentKindLabel = GatewayAgentKindLabel.fromRaw(name)

    private fun toResponse(s: AgentSession) =
        AgentResponse(
            id = s.id,
            kind = s.kind,
            cwd = s.cwd,
            createdAt = s.createdAt.toString(),
            cliSessionId = s.cliSessionId,
            stableSessionId = s.stableSessionId,
            epoch = s.epoch,
            continuation = s.continuation?.toDto(),
        )

    private fun ContinuationMetadata.toDomain() =
        AgentContinuation(
            reason = reason,
            previousEpoch = previousEpoch,
            fromSetupLabel = fromSetupLabel,
            toSetupLabel = toSetupLabel,
        )

    private fun AgentContinuation.toDto() =
        ContinuationMetadata(
            reason = reason,
            previousEpoch = previousEpoch,
            fromSetupLabel = fromSetupLabel,
            toSetupLabel = toSetupLabel,
        )

    private companion object {
        private const val REST_OBSERVATION_NAME = "agent.gateway.rest.operation"
    }
}
