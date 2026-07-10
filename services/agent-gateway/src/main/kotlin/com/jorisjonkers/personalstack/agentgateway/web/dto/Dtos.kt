package com.jorisjonkers.personalstack.agentgateway.web.dto

import com.jorisjonkers.personalstack.agentgateway.tmux.AgentKind

data class SpawnAgentRequest(
    val kind: AgentKind,
    val workspacePath: String? = null,
    val stableSessionId: String? = null,
    val epoch: Long? = null,
    val continuation: ContinuationMetadata? = null,
    // When set (session revival/continuation), resume the prior CLI
    // conversation instead of minting a fresh native session — so the
    // agent keeps its context across a shutdown, not just the terminal
    // bytes the transcript replays.
    val resumeCliSessionId: String? = null,
)

data class SendInputRequest(
    val input: String,
    val enter: Boolean = true,
)

data class StageInputRequest(
    val content: String,
    val name: String? = null,
)

data class StagedInputResponse(
    val path: String,
    val bytes: Long,
    val name: String,
)

data class AgentResponse(
    val id: String,
    val kind: AgentKind,
    val cwd: String,
    val createdAt: String,
    val cliSessionId: String? = null,
    val stableSessionId: String? = null,
    val epoch: Long = 1,
    val continuation: ContinuationMetadata? = null,
    // Milliseconds since the agent last produced output; null when unknown.
    // The control plane uses this to avoid recycling a runner mid-task.
    val idleMillis: Long? = null,
)

data class ContinuationMetadata(
    val reason: String? = null,
    val previousEpoch: Long? = null,
    val fromSetupLabel: String? = null,
    val toSetupLabel: String? = null,
)

data class CloneRequest(
    val repoUrl: String,
    val branch: String? = null,
    val intoDir: String? = null,
)

data class PushRequest(
    val repoDir: String,
    val branch: String? = null,
)

data class OpenPrRequest(
    val repoDir: String,
    val title: String,
    val body: String,
    val base: String = "main",
)

data class GitOperationResponse(
    val ok: Boolean,
    val output: String,
)

data class HeadlessRequest(
    val kind: com.jorisjonkers.personalstack.agentgateway.tmux.AgentKind,
    val prompt: String,
    val workspacePath: String? = null,
    val cliSessionId: String? = null,
    val stableSessionId: String? = null,
    val epoch: Long? = null,
    val continuation: ContinuationMetadata? = null,
    val timeoutSeconds: Long? = null,
    // Opt-in token-level streaming (Claude `--include-partial-messages`).
    // Default off so existing headless callers are unaffected.
    val partialMessages: Boolean = false,
    // When false (the default) KB_AUTO_MCP_DISABLED=1 is injected so the
    // headless worker does not fire auto-KB recall/capture hooks. Set to
    // true only for runs that explicitly need KB hook access.
    val enableKbHooks: Boolean = false,
)

data class HeadlessJobResponse(
    val id: String,
    val kind: com.jorisjonkers.personalstack.agentgateway.tmux.AgentKind,
    val status: com.jorisjonkers.personalstack.agentgateway.headless.HeadlessJobStatus,
    val exitCode: Int? = null,
    val output: String? = null,
    val createdAt: String,
    val completedAt: String? = null,
)
