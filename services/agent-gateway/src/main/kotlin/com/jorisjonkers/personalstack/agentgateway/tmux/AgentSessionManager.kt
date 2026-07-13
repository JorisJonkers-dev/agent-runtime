package com.jorisjonkers.personalstack.agentgateway.tmux

import com.jorisjonkers.personalstack.agentgateway.config.GatewayProperties
import com.jorisjonkers.personalstack.agentgateway.observability.AgentGatewayTelemetry
import io.micrometer.observation.ObservationRegistry
import jakarta.annotation.PreDestroy
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Spring-facing facade for active agent sessions on this Pod. The Pod is the
 * unit of restart, and agents-api owns the source-of-truth state, so this class
 * delegates registry, spawning, transcript, and tmux operations to focused
 * collaborators.
 */
@Component
class AgentSessionManager private constructor(
    private val components: AgentSessionComponents,
) : AgentSessionSpawnOperations by components.spawn,
    AgentSessionLifecycleOperations by components.lifecycle,
    AgentSessionRegistryOperations by components.registry,
    AgentSessionControlOperations by components.controls,
    AgentTranscriptAdminOperations by components.transcripts {
    @Autowired
    constructor(
        tmux: TmuxClient,
        props: GatewayProperties,
        transcriptStore: TranscriptStore,
        telemetry: AgentGatewayTelemetry = AgentGatewayTelemetry.NOOP,
        observationRegistry: ObservationRegistry = ObservationRegistry.NOOP,
        claudeTranscriptLocator: ClaudeTranscriptLocator = ClaudeTranscriptLocator.fromEnvironment(),
    ) : this(
        AgentSessionComponents.create(
            AgentSessionComponentDependencies(
                tmux = tmux,
                props = props,
                transcriptStore = transcriptStore,
                telemetry =
                    AgentTelemetryDependencies(
                        gateway = telemetry,
                        observationRegistry = observationRegistry,
                    ),
                claudeTranscriptLocator = claudeTranscriptLocator,
            ),
        ),
    )

    private val log = LoggerFactory.getLogger(AgentSessionManager::class.java)
    private val trimmer =
        Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "agent-transcript-maintenance").apply { isDaemon = true }
        }

    init {
        val period = components.props.transcripts.trimIntervalSeconds
        trimmer.scheduleWithFixedDelay(components.maintenance::maintain, period, period, TimeUnit.SECONDS)
        components.telemetry.recordActiveSessionCounts()
    }

    @PreDestroy
    fun shutdown() {
        trimmer.shutdownNow()
        try {
            trimmer.awaitTermination(1, TimeUnit.SECONDS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        components.lifecycle.stopAll { id, error ->
            log.warn("shutdown cleanup of agent {} failed: {}", id, error.message)
        }
        components.telemetry.recordActiveSessionCounts()
    }
}

private data class AgentSessionComponents(
    val props: GatewayProperties,
    val registry: AgentSessionRegistry,
    val telemetry: AgentSessionTelemetry,
    val spawn: AgentSessionSpawnWorkflow,
    val lifecycle: AgentSessionLifecycle,
    val controls: AgentSessionControls,
    val transcripts: AgentTranscriptAdmin,
    val maintenance: AgentTranscriptMaintenance,
) {
    companion object {
        fun create(dependencies: AgentSessionComponentDependencies): AgentSessionComponents {
            val registry = AgentSessionRegistry()
            val sessionTelemetry =
                AgentSessionTelemetry(
                    registry,
                    dependencies.telemetry.gateway,
                    dependencies.telemetry.observationRegistry,
                )
            return AgentSessionComponents(
                props = dependencies.props,
                registry = registry,
                telemetry = sessionTelemetry,
                spawn =
                    AgentSessionSpawnWorkflow(
                        tmux = dependencies.tmux,
                        props = dependencies.props,
                        transcriptStore = dependencies.transcriptStore,
                        registry = registry,
                        telemetry = sessionTelemetry,
                        claudeTranscriptLocator = dependencies.claudeTranscriptLocator,
                    ),
                lifecycle =
                    AgentSessionLifecycle(
                        dependencies.tmux,
                        dependencies.transcriptStore,
                        registry,
                        sessionTelemetry,
                    ),
                controls =
                    AgentSessionControls(
                        dependencies.tmux,
                        dependencies.props,
                        registry,
                        sessionTelemetry,
                    ),
                transcripts = AgentTranscriptAdmin(dependencies.transcriptStore, sessionTelemetry),
                maintenance =
                    AgentTranscriptMaintenance(
                        dependencies.tmux,
                        dependencies.props,
                        dependencies.transcriptStore,
                        registry,
                        sessionTelemetry,
                    ),
            )
        }
    }
}

private data class AgentSessionComponentDependencies(
    val tmux: TmuxClient,
    val props: GatewayProperties,
    val transcriptStore: TranscriptStore,
    val telemetry: AgentTelemetryDependencies,
    val claudeTranscriptLocator: ClaudeTranscriptLocator,
)

private data class AgentTelemetryDependencies(
    val gateway: AgentGatewayTelemetry,
    val observationRegistry: ObservationRegistry,
)
