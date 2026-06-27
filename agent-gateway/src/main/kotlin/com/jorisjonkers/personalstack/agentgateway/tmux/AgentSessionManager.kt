@file:Suppress("TooManyFunctions", "LargeClass", "TooGenericExceptionCaught", "LongMethod", "CyclomaticComplexMethod")

package com.jorisjonkers.personalstack.agentgateway.tmux

import com.jorisjonkers.personalstack.agentgateway.config.GatewayProperties
import com.jorisjonkers.personalstack.agentgateway.observability.AgentGatewayTelemetry
import com.jorisjonkers.personalstack.agentgateway.observability.GatewayActiveSessionsSample
import com.jorisjonkers.personalstack.agentgateway.observability.GatewayAgentKindLabel
import com.jorisjonkers.personalstack.agentgateway.observability.GatewayFailureReasonLabel
import com.jorisjonkers.personalstack.agentgateway.observability.GatewayModeLabel
import com.jorisjonkers.personalstack.agentgateway.observability.GatewayOperationLabel
import com.jorisjonkers.personalstack.agentgateway.observability.GatewayOperationTelemetry
import com.jorisjonkers.personalstack.agentgateway.observability.GatewayOutcomeLabel
import com.jorisjonkers.personalstack.agentgateway.observability.GatewayStatusLabel
import com.jorisjonkers.personalstack.agentgateway.process.ProcessFailedException
import com.jorisjonkers.personalstack.agentgateway.process.ProcessTimeoutException
import io.micrometer.observation.Observation
import io.micrometer.observation.ObservationRegistry
import jakarta.annotation.PreDestroy
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Duration
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
    private val transcriptStore: TranscriptStore,
    private val telemetry: AgentGatewayTelemetry = AgentGatewayTelemetry.NOOP,
    private val observationRegistry: ObservationRegistry = ObservationRegistry.NOOP,
    private val claudeTranscriptLocator: ClaudeTranscriptLocator = ClaudeTranscriptLocator.fromEnvironment(),
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
        recordActiveSessionCounts()
    }

    @PreDestroy
    fun shutdown() {
        trimmer.shutdownNow()
        try {
            trimmer.awaitTermination(1, TimeUnit.SECONDS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        sessions.keys.sorted().forEach { id ->
            runCatching { stop(id) }
                .onFailure { log.warn("shutdown cleanup of agent {} failed: {}", id, it.message) }
        }
        recordActiveSessionCounts()
    }

    private fun maintainTranscripts() {
        val cap = props.tmux.logCapBytes
        sessions.values.forEach { session ->
            val startedAt = Instant.now()
            runCatching {
                val stableSessionId = session.stableSessionId
                if (stableSessionId != null) {
                    val beforeTrim = transcriptStore.recoverMetadata(stableSessionId).logicalStart
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
                    val trimmed = transcriptStore.trimIfNeeded(stableSessionId)
                    if (trimmed.logicalStart > beforeTrim) {
                        recordSessionOperation(
                            operation = GatewayOperationLabel.REPLAY,
                            kind = current.kind.toTelemetryKind(),
                            outcome = GatewayOutcomeLabel.SUCCESS,
                            reason = GatewayFailureReasonLabel.NONE,
                            startedAt = startedAt,
                            observed = false,
                        )
                    }
                } else {
                    val file = session.logFile
                    if (Files.exists(file) && Files.size(file) > cap) {
                        FileChannel.open(file, StandardOpenOption.WRITE).use { it.truncate(0) }
                        log.info("trimmed agent {} log past {} bytes", session.id, cap)
                        recordSessionOperation(
                            operation = GatewayOperationLabel.REPLAY,
                            kind = session.kind.toTelemetryKind(),
                            outcome = GatewayOutcomeLabel.SUCCESS,
                            reason = GatewayFailureReasonLabel.NONE,
                            startedAt = startedAt,
                            observed = false,
                        )
                    }
                }
            }.onFailure {
                recordSessionOperation(
                    operation = GatewayOperationLabel.REPLAY,
                    kind = session.kind.toTelemetryKind(),
                    outcome = GatewayOutcomeLabel.FAILURE,
                    reason = failureReasonLabel(it),
                    startedAt = startedAt,
                    observed = false,
                    error = it,
                )
                log.warn("trim of {} failed: {}", session.logFile, it.message)
            }
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
        resumeCliSessionId: String? = null,
    ): AgentSession {
        val startedAt = Instant.now()
        val telemetryKind = kind.toTelemetryKind()
        var outcome = GatewayOutcomeLabel.SUCCESS
        var reason = GatewayFailureReasonLabel.NONE
        var failure: Throwable? = null
        val observation = startSessionObservation(GatewayOperationLabel.SPAWN, telemetryKind)
        try {
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

            val codexHome = if (kind == AgentKind.CODEX) codexSessionHome(durableStableSessionId) else null
            codexHome?.let(::prepareCodexHome)
            val (command, commandCliSessionId) = commandAndSessionIdFor(kind, cwd, resumeCliSessionId, codexHome)
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

            // Codex's native id only exists once it writes the rollout at
            // session start; capture it from the isolated home (fresh case).
            // Claude and Codex-resume already know their id at build time.
            val cliSessionId =
                commandCliSessionId
                    ?: codexHome?.takeIf { kind == AgentKind.CODEX }?.let(::captureCodexSessionId)

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
            recordActiveSessionCounts()
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
        } catch (e: Exception) {
            outcome = GatewayOutcomeLabel.FAILURE
            reason = failureReasonLabel(e)
            failure = e
            throw e
        } finally {
            recordSessionOperation(
                operation = GatewayOperationLabel.SPAWN,
                kind = telemetryKind,
                outcome = outcome,
                reason = reason,
                startedAt = startedAt,
                observation = observation,
                error = failure,
            )
        }
    }

    fun stop(id: String): Boolean {
        val startedAt = Instant.now()
        var kind = GatewayAgentKindLabel.OTHER
        var outcome = GatewayOutcomeLabel.FAILURE
        var reason = GatewayFailureReasonLabel.NOT_FOUND
        var failure: Throwable? = null
        var observation: Observation? = null
        try {
            val session = sessions.remove(id) ?: return false
            kind = session.kind.toTelemetryKind()
            observation = startSessionObservation(GatewayOperationLabel.STOP, kind)
            tmux.killSession(session.tmuxSession)
            session.stableSessionId?.let {
                transcriptStore.seal(it)
                session.transcriptLease?.let(transcriptStore::releaseLease)
            }
            outcome = GatewayOutcomeLabel.SUCCESS
            reason = GatewayFailureReasonLabel.NONE
            log.info("stopped agent {}", id)
            return true
        } catch (e: Exception) {
            outcome = GatewayOutcomeLabel.FAILURE
            reason = failureReasonLabel(e)
            failure = e
            throw e
        } finally {
            recordActiveSessionCounts()
            recordSessionOperation(
                operation = GatewayOperationLabel.STOP,
                kind = kind,
                outcome = outcome,
                reason = reason,
                startedAt = startedAt,
                observation = observation,
                error = failure,
            )
        }
    }

    fun get(id: String): AgentSession? = sessions[id]

    fun list(): List<AgentSession> = sessions.values.sortedBy { it.createdAt }

    /**
     * Milliseconds since the agent last produced output, derived from the
     * mtime of the pane's append-only log (tmux `pipe-pane` touches it on
     * every write, even with no client attached). This is the only signal of
     * "the agent is between turns / idle" that survives a disconnected client,
     * so the control plane can hold off recycling a runner until its agent has
     * gone quiet. Null when the session or its log is unknown — callers treat
     * an unknown as "not safe to recycle".
     */
    fun idleMillis(id: String): Long? {
        val session = sessions[id] ?: return null
        return runCatching {
            val lastWrite = Files.getLastModifiedTime(session.logFile).toMillis()
            (Instant.now().toEpochMilli() - lastWrite).coerceAtLeast(0L)
        }.getOrNull()
    }

    fun cleanupTranscript(stableSessionId: String): Boolean {
        val startedAt = Instant.now()
        var outcome = GatewayOutcomeLabel.SUCCESS
        var reason = GatewayFailureReasonLabel.NONE
        var failure: Throwable? = null
        try {
            val ok = transcriptStore.cleanup(transcriptStore.validateStableSessionId(stableSessionId))
            if (!ok) {
                outcome = GatewayOutcomeLabel.FAILURE
                reason = GatewayFailureReasonLabel.NOT_FOUND
            }
            return ok
        } catch (e: Exception) {
            outcome = GatewayOutcomeLabel.FAILURE
            reason = failureReasonLabel(e)
            failure = e
            throw e
        } finally {
            recordSessionOperation(
                operation = GatewayOperationLabel.REPLAY,
                kind = GatewayAgentKindLabel.OTHER,
                outcome = outcome,
                reason = reason,
                startedAt = startedAt,
                observed = false,
                error = failure,
            )
        }
    }

    fun send(
        id: String,
        input: String,
        enter: Boolean = true,
    ) = withSessionOperation(id, GatewayOperationLabel.INPUT, observed = true) { session ->
        tmux.sendKeys(session.tmuxSession, input, enter = enter)
    }

    fun stageInput(
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

    fun capture(
        id: String,
        historyLines: Int = 1_000,
    ): String =
        withSessionOperation(id, GatewayOperationLabel.REPLAY, observed = false) { session ->
            tmux.capture(session.tmuxSession, historyLines)
        }

    fun captureWithEscapes(id: String): String =
        withSessionOperation(id, GatewayOperationLabel.REPLAY, observed = false) { session ->
            tmux.captureWithEscapes(session.tmuxSession)
        }

    fun resize(
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
            val session = sessions[id] ?: error("unknown agent: $id")
            kind = session.kind.toTelemetryKind()
            if (observed) observation = startSessionObservation(operation, kind)
            return block(session)
        } catch (e: Exception) {
            outcome = GatewayOutcomeLabel.FAILURE
            reason = failureReasonLabel(e)
            failure = e
            throw e
        } finally {
            recordSessionOperation(
                operation = operation,
                kind = kind,
                outcome = outcome,
                reason = reason,
                startedAt = startedAt,
                observed = observed,
                observation = observation,
                error = failure,
            )
        }
    }

    private fun recordActiveSessionCounts() {
        val counts = sessions.values.groupingBy { it.kind }.eachCount()
        AgentKind.values().forEach { kind ->
            telemetry.recordActiveSessions(
                GatewayActiveSessionsSample(
                    status = GatewayStatusLabel.RUNNING,
                    kind = kind.toTelemetryKind(),
                    mode = GatewayModeLabel.INTERACTIVE,
                    count = counts[kind]?.toLong() ?: 0,
                ),
            )
        }
    }

    private fun startSessionObservation(
        operation: GatewayOperationLabel,
        kind: GatewayAgentKindLabel,
    ): Observation =
        Observation
            .start(SESSION_OBSERVATION_NAME, observationRegistry)
            .lowCardinalityKeyValue("operation", operation.label)
            .lowCardinalityKeyValue("kind", kind.label)
            .lowCardinalityKeyValue("mode", GatewayModeLabel.INTERACTIVE.label)

    private fun recordSessionOperation(
        operation: GatewayOperationLabel,
        kind: GatewayAgentKindLabel,
        outcome: GatewayOutcomeLabel,
        reason: GatewayFailureReasonLabel,
        startedAt: Instant,
        observed: Boolean = true,
        observation: Observation? = null,
        error: Throwable? = null,
    ) {
        val activeObservation = observation ?: if (observed) startSessionObservation(operation, kind) else null
        activeObservation
            ?.lowCardinalityKeyValue("outcome", outcome.label)
            ?.lowCardinalityKeyValue("reason", reason.label)
        error?.let { activeObservation?.error(it) }
        activeObservation?.stop()
        telemetry.recordOperation(
            GatewayOperationTelemetry(
                operation = operation,
                kind = kind,
                mode = GatewayModeLabel.INTERACTIVE,
                outcome = outcome,
                reason = reason,
                duration = Duration.between(startedAt, Instant.now()),
            ),
        )
    }

    private fun failureReasonLabel(error: Throwable): GatewayFailureReasonLabel =
        when (error) {
            is IllegalArgumentException -> GatewayFailureReasonLabel.INVALID_REQUEST
            is IllegalStateException ->
                if (error.message?.startsWith("unknown agent:") == true) {
                    GatewayFailureReasonLabel.NOT_FOUND
                } else {
                    GatewayFailureReasonLabel.OTHER
                }
            is IOException -> GatewayFailureReasonLabel.IO_ERROR
            is ProcessFailedException -> GatewayFailureReasonLabel.PROCESS_EXITED
            is ProcessTimeoutException -> GatewayFailureReasonLabel.TIMEOUT
            is SecurityException -> GatewayFailureReasonLabel.PERMISSION_DENIED
            is InterruptedException -> GatewayFailureReasonLabel.CANCELLED
            else -> GatewayFailureReasonLabel.UNKNOWN
        }

    private fun AgentKind.toTelemetryKind(): GatewayAgentKindLabel = GatewayAgentKindLabel.fromRaw(name)

    /**
     * Build the CLI command and return the native session id alongside it.
     *
     * For Claude: on a fresh start a new UUID is passed as `--session-id
     * <uuid>` so the CLI process has a stable native identity without
     * inheriting another conversation. On revival ([resumeCliSessionId]
     * set) the prior conversation is resumed via `--resume <id>` only when
     * Claude's local transcript still exists under ~/.claude/projects for
     * this cwd; otherwise `--session-id <id>` starts fresh while preserving
     * the persisted native id across epochs.
     *
     * For Codex: Codex has no `--session-id` to pre-set, so each session
     * runs under its own [codexHome] (auth/config symlinked in by
     * [prepareCodexHome]). That isolation makes the rollout this session
     * creates the only one in its sessions dir, so its id is captured after
     * launch ([captureCodexSessionId]) and `codex resume <id>` resumes that
     * exact rollout on revival — avoiding the `codex resume --last`
     * cross-session risk on the shared credentials PVC. The id is unknown at
     * command-build time, so the fresh case returns null here and the caller
     * captures it post-launch.
     *
     * Shell has no session id.
     */
    private fun commandAndSessionIdFor(
        kind: AgentKind,
        cwd: String,
        resumeCliSessionId: String? = null,
        codexHome: Path? = null,
    ): Pair<List<String>, String?> =
        when (kind) {
            AgentKind.CLAUDE -> {
                val cliSessionId = resumeCliSessionId ?: UUID.randomUUID().toString()
                val sessionArgs =
                    if (resumeCliSessionId == null) {
                        listOf("--session-id", cliSessionId)
                    } else if (claudeTranscriptLocator.transcriptExists(cwd, resumeCliSessionId)) {
                        log.info(
                            "claude revival selected resume sessionId={} reason=transcript-exists",
                            resumeCliSessionId,
                        )
                        listOf("--resume", resumeCliSessionId)
                    } else {
                        log.info(
                            "claude revival selected fresh-with-stable-id sessionId={} reason=transcript-missing",
                            resumeCliSessionId,
                        )
                        listOf("--session-id", resumeCliSessionId)
                    }
                (listOf(props.cli.claude) + props.cli.claudeArgs + sessionArgs) to cliSessionId
            }
            AgentKind.CODEX -> {
                // `env CODEX_HOME=<home>` scopes this session's rollouts
                // independently of the shared tmux server environment.
                val envPrefix = codexHome?.let { listOf("env", "CODEX_HOME=$it") } ?: emptyList()
                val resumeArgs = resumeCliSessionId?.let { listOf("resume", it) } ?: emptyList()
                (envPrefix + listOf(props.cli.codex) + props.cli.codexArgs + resumeArgs) to resumeCliSessionId
            }
            AgentKind.SHELL -> listOf("/bin/bash", "-l") to null
        }

    private fun codexSessionHome(stableSessionId: String): Path =
        Path(props.codex.home).resolve(props.codex.sessionHomesSubdir).resolve(stableSessionId)

    // Isolate this session's Codex state: its own sessions/ dir, with the
    // shared OAuth + config symlinked back to the credentials home so token
    // refresh still writes through to the one canonical auth.json. Best
    // effort — if it fails, Codex still launches and id capture just misses.
    private fun prepareCodexHome(home: Path) {
        runCatching {
            Files.createDirectories(home.resolve("sessions"))
            for (name in CODEX_SHARED_FILES) {
                val src = Path(props.codex.home).resolve(name)
                val link = home.resolve(name)
                if (Files.exists(link, LinkOption.NOFOLLOW_LINKS) || !Files.exists(src)) continue
                Files.createSymbolicLink(link, src)
            }
        }.onFailure { log.warn("could not prepare codex home {}: {}", home, it.message) }
    }

    // Poll the isolated sessions dir for the rollout Codex writes at session
    // start and read its UUID from the filename. Isolation guarantees a single
    // rollout, so there is no cross-session ambiguity; on timeout we return
    // null and the session simply starts fresh on its next revival.
    private fun captureCodexSessionId(codexHome: Path): String? {
        val sessionsDir = codexHome.resolve("sessions")
        val deadline = Instant.now().plusMillis(props.codex.captureTimeoutMs)
        while (Instant.now().isBefore(deadline)) {
            newestRolloutId(sessionsDir)?.let { return it }
            try {
                Thread.sleep(props.codex.capturePollMs)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                break
            }
        }
        log.warn("codex session id not captured within {}ms under {}", props.codex.captureTimeoutMs, sessionsDir)
        return null
    }

    private fun newestRolloutId(sessionsDir: Path): String? {
        if (!Files.isDirectory(sessionsDir)) return null
        val newest =
            Files.walk(sessionsDir).use { stream ->
                stream
                    .filter { Files.isRegularFile(it) && ROLLOUT_FILE.matches(it.fileName.toString()) }
                    .max(compareBy { runCatching { Files.getLastModifiedTime(it).toMillis() }.getOrDefault(0L) })
                    .orElse(null)
            } ?: return null
        return ROLLOUT_ID.find(newest.fileName.toString())?.groupValues?.get(1)
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
        private const val SESSION_OBSERVATION_NAME = "agent.gateway.session.operation"
        private val SAFE_NAME_CHARS = Regex("[^A-Za-z0-9._-]+")
        private val ROLLOUT_FILE = Regex("""^rollout-.*\.jsonl$""")
        private val ROLLOUT_ID =
            Regex("""([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})\.jsonl$""")
        private val CODEX_SHARED_FILES = listOf("auth.json", "config.toml")
        private val STAGED_INPUT_TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS").withZone(ZoneOffset.UTC)
    }
}
