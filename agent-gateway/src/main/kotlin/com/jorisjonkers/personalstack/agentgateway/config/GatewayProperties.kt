package com.jorisjonkers.personalstack.agentgateway.config

import org.springframework.boot.context.properties.ConfigurationProperties

private const val DEFAULT_LOG_CAP_BYTES = 8L * 1024 * 1024
private const val DEFAULT_STAGED_INPUT_MAX_BYTES = 5L * 1024 * 1024
private const val DEFAULT_TRANSCRIPT_SEGMENT_BYTES = 2L * 1024 * 1024
private const val DEFAULT_TRANSCRIPT_CAP_BYTES = 64L * 1024 * 1024
private const val DEFAULT_TRANSCRIPT_RETENTION_SECONDS = 7L * 24 * 60 * 60
private const val DEFAULT_CODEX_CAPTURE_TIMEOUT_MS = 8_000L
private const val DEFAULT_CODEX_CAPTURE_POLL_MS = 100L

@ConfigurationProperties(prefix = "agent-gateway")
data class GatewayProperties(
    val workspaceRoot: String,
    val tmux: Tmux,
    val cli: Cli,
    val runner: Runner = Runner(),
    val stagedInputs: StagedInputs = StagedInputs(),
    val transcripts: Transcripts = Transcripts(),
    val codex: Codex = Codex(),
) {
    data class Tmux(
        val socketName: String,
        val stateDir: String,
        // Poll cadence for the pipe-pane log tailer. Lower = more
        // responsive streamed output at the cost of more wakeups.
        val tailIntervalMs: Long = 15,
        // The pipe-pane log is only a streaming conduit (the live screen
        // lives in tmux, scrollback in the browser), so it is truncated
        // once it outgrows this cap to keep the runner disk bounded.
        val logCapBytes: Long = DEFAULT_LOG_CAP_BYTES,
        val logTrimIntervalSeconds: Long = 30,
    )

    data class Cli(
        val claude: String,
        val codex: String,
        // The runner Pod is the outer sandbox. Docker-socket-enabled runners
        // are host-equivalent by design for Docker/Testcontainers, while the
        // CLIs still launch with every approval/permission/sandbox prompt
        // bypassed. Kept as config so a flag rename upstream is a
        // redeploy-free value flip.
        val claudeArgs: List<String> = emptyList(),
        val codexArgs: List<String> = emptyList(),
    )

    data class Runner(
        val setupId: String = "",
        val setupVersion: Long = 0,
        val setupHash: String = "",
        val generation: Long = 0,
    )

    data class StagedInputs(
        val dirName: String = ".agent-inputs",
        val maxBytes: Long = DEFAULT_STAGED_INPUT_MAX_BYTES,
    )

    data class Transcripts(
        val dirName: String = ".agent-transcripts",
        val segmentBytes: Long = DEFAULT_TRANSCRIPT_SEGMENT_BYTES,
        val capBytes: Long = DEFAULT_TRANSCRIPT_CAP_BYTES,
        val trimIntervalSeconds: Long = 30,
        val leaseTtlSeconds: Long = 120,
        val retentionSeconds: Long = DEFAULT_TRANSCRIPT_RETENTION_SECONDS,
    )

    // Codex has no `--session-id` to pre-set, so each interactive session
    // gets its own isolated CODEX_HOME (auth.json / config.toml symlinked
    // from [home]). That makes the rollout this session creates the only
    // one in its sessions dir, so its UUID can be captured unambiguously
    // for `codex resume <id>` on revival.
    data class Codex(
        // Shared creds home — must match the runner Pod's CODEX_HOME env.
        val home: String = "/home/agent/.codex",
        // Per-session homes live under <home>/<sessionHomesSubdir>/<stableSessionId>.
        val sessionHomesSubdir: String = "session-homes",
        val captureTimeoutMs: Long = DEFAULT_CODEX_CAPTURE_TIMEOUT_MS,
        val capturePollMs: Long = DEFAULT_CODEX_CAPTURE_POLL_MS,
    )
}
