package com.jorisjonkers.personalstack.agentgateway.tmux

import com.jorisjonkers.personalstack.agentgateway.config.GatewayProperties
import com.jorisjonkers.personalstack.agentgateway.observability.AgentGatewayTelemetry
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.time.Clock
import java.util.Properties
import java.util.UUID
import kotlin.io.path.Path

internal class TranscriptStoreContext(
    val props: GatewayProperties,
    val clock: Clock,
    val telemetry: AgentGatewayTelemetry,
) {
    val paths = TranscriptStorePaths(this)

    fun validateStableSessionId(value: String): String = UUID.fromString(value).toString()

    fun root(): Path {
        val workspace = Path(props.workspaceRoot).toAbsolutePath().normalize()
        val root = workspace.resolve(props.transcripts.dirName).normalize()
        require(root.startsWith(workspace)) { "transcript directory must stay inside the workspace" }
        Files.createDirectories(root)
        return root
    }

    fun lockFor(stableSessionId: String): Any = locks.computeIfAbsent(stableSessionId) { Any() }

    fun writePropertiesAtomic(
        file: Path,
        properties: Properties,
    ) {
        Files.createDirectories(file.parent)
        val tmp = file.resolveSibling("${file.fileName}.tmp-${UUID.randomUUID()}")
        Files.newOutputStream(tmp, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).use { out ->
            properties.store(out, null)
        }
        runCatching {
            Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        }.getOrElse {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    companion object {
        private val locks = java.util.concurrent.ConcurrentHashMap<String, Any>()
    }
}
