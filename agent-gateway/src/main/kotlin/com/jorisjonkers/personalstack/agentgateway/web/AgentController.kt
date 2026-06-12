package com.jorisjonkers.personalstack.agentgateway.web

import com.jorisjonkers.personalstack.agentgateway.tmux.AgentContinuation
import com.jorisjonkers.personalstack.agentgateway.tmux.AgentSession
import com.jorisjonkers.personalstack.agentgateway.tmux.AgentSessionManager
import com.jorisjonkers.personalstack.agentgateway.web.dto.AgentResponse
import com.jorisjonkers.personalstack.agentgateway.web.dto.ContinuationMetadata
import com.jorisjonkers.personalstack.agentgateway.web.dto.SendInputRequest
import com.jorisjonkers.personalstack.agentgateway.web.dto.SpawnAgentRequest
import com.jorisjonkers.personalstack.agentgateway.web.dto.StageInputRequest
import com.jorisjonkers.personalstack.agentgateway.web.dto.StagedInputResponse
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/agents")
class AgentController(
    private val sessions: AgentSessionManager,
) {
    @GetMapping
    fun list(): List<AgentResponse> = sessions.list().map(::toResponse)

    @PostMapping
    fun spawn(
        @RequestBody req: SpawnAgentRequest,
    ): ResponseEntity<AgentResponse> {
        val session =
            sessions.spawn(
                req.kind,
                req.workspacePath,
                req.stableSessionId,
                req.epoch,
                req.continuation?.toDomain(),
            )
        return ResponseEntity.status(HttpStatus.CREATED).body(toResponse(session))
    }

    @DeleteMapping("/transcripts/{stableSessionId}")
    fun cleanupTranscript(
        @PathVariable stableSessionId: String,
    ): ResponseEntity<Void> {
        val ok = sessions.cleanupTranscript(stableSessionId)
        return if (ok) ResponseEntity.noContent().build() else ResponseEntity.notFound().build()
    }

    @GetMapping("/{id}")
    fun get(
        @PathVariable id: String,
    ): ResponseEntity<AgentResponse> =
        sessions.get(id)?.let { ResponseEntity.ok(toResponse(it)) }
            ?: ResponseEntity.notFound().build()

    @PostMapping("/{id}/send")
    fun send(
        @PathVariable id: String,
        @RequestBody req: SendInputRequest,
    ): ResponseEntity<Void> {
        sessions.send(id, req.input, req.enter)
        return ResponseEntity.accepted().build()
    }

    @PostMapping("/{id}/staged-inputs")
    fun stageInput(
        @PathVariable id: String,
        @RequestBody req: StageInputRequest,
    ): ResponseEntity<StagedInputResponse> {
        val staged = sessions.stageInput(id, req.content, req.name)
        return ResponseEntity
            .status(HttpStatus.CREATED)
            .body(StagedInputResponse(path = staged.path, bytes = staged.bytes, name = staged.name))
    }

    @GetMapping("/{id}/capture")
    fun capture(
        @PathVariable id: String,
    ): ResponseEntity<Map<String, String>> {
        val text = sessions.capture(id)
        return ResponseEntity.ok(mapOf("text" to text))
    }

    @DeleteMapping("/{id}")
    fun stop(
        @PathVariable id: String,
    ): ResponseEntity<Void> {
        val ok = sessions.stop(id)
        return if (ok) ResponseEntity.noContent().build() else ResponseEntity.notFound().build()
    }

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
}
