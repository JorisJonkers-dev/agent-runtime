package com.jorisjonkers.personalstack.agentgateway.config

import org.springframework.boot.context.properties.ConfigurationProperties

private const val DEFAULT_LOG_CAP_BYTES = 8L * 1024 * 1024
private const val DEFAULT_STAGED_INPUT_MAX_BYTES = 5L * 1024 * 1024

@ConfigurationProperties(prefix = "agent-gateway")
data class GatewayProperties(
    val workspaceRoot: String,
    val tmux: Tmux,
    val cli: Cli,
    val git: Git,
    val stagedInputs: StagedInputs = StagedInputs(),
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

    data class Git(
        val deployKeyDir: String,
    )

    data class StagedInputs(
        val dirName: String = ".agent-inputs",
        val maxBytes: Long = DEFAULT_STAGED_INPUT_MAX_BYTES,
    )
}
