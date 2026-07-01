package com.jorisjonkers.personalstack.agentgateway.web

import com.jorisjonkers.personalstack.agentgateway.observability.AgentGatewayTelemetry
import com.jorisjonkers.personalstack.agentgateway.observability.GatewayFailureReasonLabel
import com.jorisjonkers.personalstack.agentgateway.observability.GatewayOperationLabel
import com.jorisjonkers.personalstack.agentgateway.observability.GatewayOperationTelemetry
import com.jorisjonkers.personalstack.agentgateway.observability.GatewayOutcomeLabel
import com.jorisjonkers.personalstack.agentgateway.process.ProcessFailedException
import com.jorisjonkers.personalstack.agentgateway.process.ProcessRunner
import com.jorisjonkers.personalstack.agentgateway.tmux.AgentContinuation
import com.jorisjonkers.personalstack.agentgateway.tmux.AgentKind
import com.jorisjonkers.personalstack.agentgateway.tmux.AgentSession
import com.jorisjonkers.personalstack.agentgateway.tmux.AgentSessionManager
import com.jorisjonkers.personalstack.agentgateway.tmux.AgentSpawnRequest
import com.jorisjonkers.personalstack.agentgateway.tmux.StagedInput
import io.micrometer.observation.Observation
import io.micrometer.observation.ObservationHandler
import io.micrometer.observation.ObservationRegistry
import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList

class AgentControllerTest {
    private val sessions = mockk<AgentSessionManager>()
    private val mockMvc: MockMvc =
        MockMvcBuilders
            .standaloneSetup(AgentController(sessions))
            .setControllerAdvice(ErrorAdvice())
            .build()

    private val sample =
        AgentSession(
            id = "abc12345",
            kind = AgentKind.CLAUDE,
            tmuxSession = "agent-abc12345",
            logFile = Path.of("/tmp/agent.log"),
            cwd = "/workspace/repo",
            createdAt = Instant.parse("2026-05-19T10:00:00Z"),
            stableSessionId = "11111111-1111-1111-1111-111111111111",
            epoch = 2,
            continuation =
                AgentContinuation(
                    reason = "restart",
                    previousEpoch = 1,
                    fromSetupLabel = "Default runner",
                    toSetupLabel = "GPU runner",
                ),
        )

    @Test
    fun `POST agents spawns and returns 201 with session`() {
        every {
            sessions.spawn(
                AgentSpawnRequest(
                    kind = AgentKind.CLAUDE,
                    workspacePath = "/workspace/repo",
                    stableSessionId = "11111111-1111-1111-1111-111111111111",
                    epoch = 2,
                    continuation =
                        AgentContinuation(
                            reason = "restart",
                            previousEpoch = 1,
                            fromSetupLabel = "Default runner",
                            toSetupLabel = "GPU runner",
                        ),
                    resumeCliSessionId = "native-old",
                ),
            )
        } returns sample
        mockMvc
            .perform(
                post("/agents")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
                        {
                          "kind":"CLAUDE",
                          "workspacePath":"/workspace/repo",
                          "stableSessionId":"11111111-1111-1111-1111-111111111111",
                          "epoch":2,
                          "continuation":{
                            "reason":"restart",
                            "previousEpoch":1,
                            "fromSetupLabel":"Default runner",
                            "toSetupLabel":"GPU runner"
                          },
                          "resumeCliSessionId":"native-old"
                        }
                        """.trimIndent(),
                    ),
            ).andExpect(status().isCreated)
            .andExpect(jsonPath("$.id").value("abc12345"))
            .andExpect(jsonPath("$.kind").value("CLAUDE"))
            .andExpect(jsonPath("$.stableSessionId").value("11111111-1111-1111-1111-111111111111"))
            .andExpect(jsonPath("$.epoch").value(2))
            .andExpect(jsonPath("$.continuation.reason").value("restart"))
            .andExpect(jsonPath("$.continuation.fromSetupLabel").value("Default runner"))
            .andExpect(jsonPath("$.continuation.toSetupLabel").value("GPU runner"))
    }

    @Test
    fun `GET agents lists sessions`() {
        every { sessions.list() } returns listOf(sample)
        mockMvc
            .perform(get("/agents"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$[0].id").value("abc12345"))
    }

    @Test
    fun `GET agents id returns 404 when unknown`() {
        every { sessions.get("nope") } returns null
        mockMvc.perform(get("/agents/nope")).andExpect(status().isNotFound)
    }

    @Test
    fun `DELETE agents id returns 204 on success`() {
        every { sessions.stop("abc12345") } returns true
        mockMvc.perform(delete("/agents/abc12345")).andExpect(status().isNoContent)
    }

    @Test
    fun `DELETE transcript cleanup returns 204 on success`() {
        every { sessions.cleanupTranscript("11111111-1111-1111-1111-111111111111") } returns true
        mockMvc
            .perform(delete("/agents/transcripts/11111111-1111-1111-1111-111111111111"))
            .andExpect(status().isNoContent)
    }

    @Test
    fun `DELETE transcript cleanup returns 404 when stable session is unknown`() {
        every { sessions.cleanupTranscript("22222222-2222-2222-2222-222222222222") } returns false
        mockMvc
            .perform(delete("/agents/transcripts/22222222-2222-2222-2222-222222222222"))
            .andExpect(status().isNotFound)
    }

    @Test
    fun `POST agents id send returns 202`() {
        every { sessions.send("abc12345", "hi", true) } returns Unit
        mockMvc
            .perform(
                post("/agents/abc12345/send")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"input":"hi","enter":true}"""),
            ).andExpect(status().isAccepted)
    }

    @Test
    fun `POST agents id staged-inputs returns staged file metadata`() {
        every {
            sessions.stageInput("abc12345", "large document", "source.txt")
        } returns StagedInput(path = "/workspace/.agent-inputs/20260604-source.txt", bytes = 14, name = "source.txt")

        mockMvc
            .perform(
                post("/agents/abc12345/staged-inputs")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"content":"large document","name":"source.txt"}"""),
            ).andExpect(status().isCreated)
            .andExpect(jsonPath("$.path").value("/workspace/.agent-inputs/20260604-source.txt"))
            .andExpect(jsonPath("$.bytes").value(14))
            .andExpect(jsonPath("$.name").value("source.txt"))
    }

    @Test
    fun `POST agents id staged-inputs returns 400 on invalid content`() {
        every {
            sessions.stageInput("abc12345", "", "source.txt")
        } throws IllegalArgumentException("staged input content is empty")

        mockMvc
            .perform(
                post("/agents/abc12345/staged-inputs")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"content":"","name":"source.txt"}"""),
            ).andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.error").value("staged input content is empty"))
    }

    @Test
    @Suppress("LongMethod")
    fun `records REST terminal outcomes with bounded labels`() {
        val localSessions = mockk<AgentSessionManager>()
        val telemetry = RecordingTelemetry()
        val observations = CopyOnWriteArrayList<Map<String, String>>()
        val observationRegistry =
            ObservationRegistry.create().apply {
                observationConfig().observationHandler(
                    object : ObservationHandler<Observation.Context> {
                        override fun supportsContext(context: Observation.Context): Boolean = true

                        override fun onStop(context: Observation.Context) {
                            observations +=
                                context.lowCardinalityKeyValues.associate { keyValue ->
                                    keyValue.key to keyValue.value
                                }
                        }
                    },
                )
            }
        val localMvc =
            MockMvcBuilders
                .standaloneSetup(AgentController(localSessions, telemetry, observationRegistry))
                .setControllerAdvice(ErrorAdvice())
                .build()

        every { localSessions.spawn(AgentSpawnRequest(kind = AgentKind.SHELL)) } returns
            sample.copy(kind = AgentKind.SHELL)
        every { localSessions.send("abc12345", "hi", true) } returns Unit
        every { localSessions.cleanupTranscript("11111111-1111-1111-1111-111111111111") } returns true
        every { localSessions.stop("missing") } returns false
        every {
            localSessions.stageInput("abc12345", "", "source.txt")
        } throws IllegalArgumentException("staged input content is empty")
        every {
            localSessions.capture("boom")
        } throws ProcessFailedException(listOf("tmux"), ProcessRunner.Result(exitCode = 1, stdout = "", stderr = "bad"))

        localMvc
            .perform(post("/agents").contentType(MediaType.APPLICATION_JSON).content("""{"kind":"SHELL"}"""))
            .andExpect(status().isCreated)
        localMvc
            .perform(
                post("/agents/abc12345/send")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"input":"hi","enter":true}"""),
            ).andExpect(status().isAccepted)
        localMvc
            .perform(delete("/agents/transcripts/11111111-1111-1111-1111-111111111111"))
            .andExpect(status().isNoContent)
        localMvc.perform(delete("/agents/missing")).andExpect(status().isNotFound)
        localMvc
            .perform(
                post("/agents/abc12345/staged-inputs")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"content":"","name":"source.txt"}"""),
            ).andExpect(status().isBadRequest)
        localMvc.perform(get("/agents/boom/capture")).andExpect(status().isInternalServerError)

        assertThat(telemetry.operations.map { Triple(it.operation, it.outcome, it.reason) })
            .contains(
                Triple(GatewayOperationLabel.SPAWN, GatewayOutcomeLabel.SUCCESS, GatewayFailureReasonLabel.NONE),
                Triple(GatewayOperationLabel.INPUT, GatewayOutcomeLabel.SUCCESS, GatewayFailureReasonLabel.NONE),
                Triple(GatewayOperationLabel.REPLAY, GatewayOutcomeLabel.SUCCESS, GatewayFailureReasonLabel.NONE),
                Triple(GatewayOperationLabel.STOP, GatewayOutcomeLabel.FAILURE, GatewayFailureReasonLabel.NOT_FOUND),
                Triple(
                    GatewayOperationLabel.INPUT,
                    GatewayOutcomeLabel.FAILURE,
                    GatewayFailureReasonLabel.INVALID_REQUEST,
                ),
                Triple(GatewayOperationLabel.REPLAY, GatewayOutcomeLabel.FAILURE, GatewayFailureReasonLabel.UNKNOWN),
            )
        assertThat(observations.map { it["outcome"] })
            .contains("success", "accepted", "no_content", "not_found", "malformed_input", "failure")
        assertThat(telemetry.operations.flatMap { it.labels() })
            .doesNotContain(
                "abc12345",
                "11111111-1111-1111-1111-111111111111",
                "/workspace/repo",
                "source.txt",
            )
        assertThat(observations.flatMap { it.values })
            .doesNotContain(
                "abc12345",
                "11111111-1111-1111-1111-111111111111",
                "/workspace/repo",
                "source.txt",
            )
    }

    private fun GatewayOperationTelemetry.labels(): List<String> =
        listOf(operation.label, kind.label, mode.label, outcome.label, reason.label)

    private class RecordingTelemetry : AgentGatewayTelemetry {
        val operations = CopyOnWriteArrayList<GatewayOperationTelemetry>()

        override fun recordOperation(event: GatewayOperationTelemetry) {
            operations += event
        }
    }
}
