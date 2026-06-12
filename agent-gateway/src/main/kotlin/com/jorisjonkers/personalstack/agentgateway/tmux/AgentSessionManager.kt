package com.jorisjonkers.personalstack.agentgateway.tmux

import com.jorisjonkers.personalstack.agentgateway.config.GatewayProperties
import com.jorisjonkers.personalstack.agentgateway.process.ProcessFailedException
import com.jorisjonkers.personalstack.agentgateway.process.ProcessTimeoutException
import jakarta.annotation.PreDestroy
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.io.path.Path

/**
 * In-memory registry of active agents on this Pod. The Pod is the
 * unit of restart, and agents-api owns the source-of-truth state,
 * so persisting here would double-bookkeeper for no win.
 *
 * Concurrency: ConcurrentHashMap is enough — the only racy operation
 * is spawn-vs-stop on the same id, and that's a caller bug worth
 * surfacing as a 409.
 */
@Component
class AgentSessionManager(
    private val tmux: TmuxClient,
    private val props: GatewayProperties,
    private val transcriptStore: TranscriptStore = TranscriptStore(props),
) {
    private val log = LoggerFactory.getLogger(AgentSessionManager::class.java)
    private val sessions = ConcurrentHashMap<String, AgentSession>()

    // Durable transcripts are capped by deleting old closed segments.
    // Legacy non-durable pipe logs are still truncated in place.
    private val trimmer =
        Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "agent-transcript-maintenance").apply { isDaemon = true }
        }

    init {
        val period = props.transcripts.trimIntervalSeconds
        trimmer.scheduleWithFixedDelay(::maintainTranscripts, period, period, TimeUnit.SECONDS)
    }

    @PreDestroy
    fun shutdown() {
        trimmer.shutdownNow()
    }

    private fun maintainTranscripts() {
        val cap = props.tmux.logCapBytes
        sessions.values.forEach { session ->
            runCatching {
                val stableSessionId = session.stableSessionId
                if (stableSessionId != null) {
                    val current =
                        session.transcriptLease
                            ?.let(transcriptStore::renewLease)
                            ?.let { session.copy(transcriptLease = it) }
                            ?: session
                    if (current !== session) sessions[session.id] = current
                    transcriptStore.rotateIfNeeded(stableSessionId)
                    val active = transcriptStore.activeSegmentPath(stableSessionId)
                    if (active != current.logFile) {
                        tmux.startPipeToFile(current.tmuxSession, active)
                        sessions[current.id] = current.copy(logFile = active, transcriptFile = active)
                        log.info("rotated agent {} transcript pipe to {}", current.id, active)
                    }
                    transcriptStore.trimIfNeeded(stableSessionId)
                } else {
                    val file = session.logFile
                    if (Files.exists(file) && Files.size(file) > cap) {
                        FileChannel.open(file, StandardOpenOption.WRITE).use { it.truncate(0) }
                        log.info("trimmed agent {} log past {} bytes", session.id, cap)
                    }
                }
            }.onFailure { log.warn("trim of {} failed: {}", session.logFile, it.message) }
        }
    }

    // Lease cleanup must stay adjacent to transcript and tmux startup ordering.
    @Suppress("CyclomaticComplexMethod", "LongMethod", "ThrowsCount")
    fun spawn(
        kind: AgentKind,
        workspacePath: String? = null,
        stableSessionId: String? = null,
        epoch: Long? = null,
        continuation: AgentContinuation? = null,
    ): AgentSession {
        val id = UUID.randomUUID().toString().substring(0, 8)
        val tmuxSession = "agent-$id"
        val cwd = workspacePath ?: props.workspaceRoot
        val durableStableSessionId =
            stableSessionId?.let(transcriptStore::validateStableSessionId)
                ?: UUID.randomUUID().toString()
        val durableEpoch = epoch ?: 1
        require(durableEpoch > 0) { "epoch must be positive" }
        val lease = transcriptStore.acquireLease(durableStableSessionId, tmuxSession, durableEpoch)
        val logFile: Path =
            try {
                transcriptStore.open(durableStableSessionId, durableEpoch)
                if (durableEpoch > 1 || continuation != null) {
                    transcriptStore.appendContinuationDelimiter(durableStableSessionId, durableEpoch, continuation)
                }
                transcriptStore.activeSegmentPath(durableStableSessionId)
            } catch (e: IOException) {
                transcriptStore.releaseLease(lease)
                throw e
            } catch (e: NumberFormatException) {
                transcriptStore.releaseLease(lease)
                throw e
            } catch (e: IllegalArgumentException) {
                transcriptStore.releaseLease(lease)
                throw e
            } catch (e: IllegalStateException) {
                transcriptStore.releaseLease(lease)
                throw e
            }

        val (command, cliSessionId) = commandAndSessionIdFor(kind)
        try {
            tmux.newSession(tmuxSession, command, cwd)
            tmux.startPipeToFile(tmuxSession, logFile)
        } catch (e: IOException) {
            transcriptStore.releaseLease(lease)
            throw e
        } catch (e: InterruptedException) {
            transcriptStore.releaseLease(lease)
            throw e
        } catch (e: ProcessFailedException) {
            transcriptStore.releaseLease(lease)
            throw e
        } catch (e: ProcessTimeoutException) {
            transcriptStore.releaseLease(lease)
            throw e
        } catch (e: SecurityException) {
            transcriptStore.releaseLease(lease)
            throw e
        }

        val session =
            AgentSession(
                id = id,
                kind = kind,
                tmuxSession = tmuxSession,
                logFile = logFile,
                cwd = cwd,
                createdAt = Instant.now(),
                cliSessionId = cliSessionId,
                stableSessionId = durableStableSessionId,
                epoch = durableEpoch,
                continuation = continuation,
                transcriptFile = logFile,
                transcriptLease = lease,
            )
        sessions[id] = session
        log.info(
            "spawned {} agent {} ({}) in {} cliSessionId={} stableSessionId={} epoch={}",
            kind,
            id,
            tmuxSession,
            cwd,
            cliSessionId,
            durableStableSessionId,
            durableEpoch,
        )
        return session
    }

    fun stop(id: String): Boolean {
        val session = sessions.remove(id) ?: return false
        tmux.killSession(session.tmuxSession)
        session.stableSessionId?.let {
            transcriptStore.seal(it)
            session.transcriptLease?.let(transcriptStore::releaseLease)
        }
        log.info("stopped agent {}", id)
        return true
    }

    fun get(id: String): AgentSession? = sessions[id]

    fun list(): List<AgentSession> = sessions.values.sortedBy { it.createdAt }

    fun cleanupTranscript(stableSessionId: String): Boolean =
        transcriptStore.cleanup(transcriptStore.validateStableSessionId(stableSessionId))

    fun send(
        id: String,
        input: String,
        enter: Boolean = true,
    ) {
        val session = sessions[id] ?: error("unknown agent: $id")
        tmux.sendKeys(session.tmuxSession, input, enter = enter)
    }

    fun stageInput(
        id: String,
        content: String,
        requestedName: String?,
    ): StagedInput {
        val session = sessions[id] ?: error("unknown agent: $id")
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
        return StagedInput(path = target.toString(), bytes = bytes.size.toLong(), name = safeName)
    }

    fun capture(
        id: String,
        historyLines: Int = 1_000,
    ): String {
        val session = sessions[id] ?: error("unknown agent: $id")
        return tmux.capture(session.tmuxSession, historyLines)
    }

    fun captureWithEscapes(id: String): String {
        val session = sessions[id] ?: error("unknown agent: $id")
        return tmux.captureWithEscapes(session.tmuxSession)
    }

    fun resize(
        id: String,
        cols: Int,
        rows: Int,
    ) {
        val session = sessions[id] ?: error("unknown agent: $id")
        tmux.resize(session.tmuxSession, cols, rows)
    }

    /**
     * Build the CLI command and return the native session id alongside it.
     *
     * For Claude: a fresh UUID is generated and passed as `--session-id
     * <uuid>` so the CLI process has a stable native identity without
     * inheriting another conversation.
     *
     * For Codex: launch the interactive CLI directly. `codex resume --last`
     * is intentionally not used here because it can attach a new gateway
     * agent to a different saved Codex session on the shared credentials PVC.
     *
     * Shell has no session id.
     */
    private fun commandAndSessionIdFor(kind: AgentKind): Pair<List<String>, String?> =
        when (kind) {
            AgentKind.CLAUDE -> {
                val cliSessionId = UUID.randomUUID().toString()
                val cmd =
                    listOf(props.cli.claude) + props.cli.claudeArgs +
                        listOf("--session-id", cliSessionId)
                cmd to cliSessionId
            }
            AgentKind.CODEX -> (listOf(props.cli.codex) + props.cli.codexArgs) to null
            AgentKind.SHELL -> listOf("/bin/bash", "-l") to null
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

    companion object {
        private const val DEFAULT_STAGED_INPUT_NAME = "input.txt"
        private const val ID_PREVIEW_CHARS = 8
        private const val MAX_STAGED_INPUT_NAME_CHARS = 80
        private val SAFE_NAME_CHARS = Regex("[^A-Za-z0-9._-]+")
        private val STAGED_INPUT_TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS").withZone(ZoneOffset.UTC)
    }
}
