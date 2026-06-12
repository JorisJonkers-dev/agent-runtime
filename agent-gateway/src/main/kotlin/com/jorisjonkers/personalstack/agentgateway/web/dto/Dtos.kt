package com.jorisjonkers.personalstack.agentgateway.web.dto

import com.jorisjonkers.personalstack.agentgateway.tmux.AgentKind

data class SpawnAgentRequest(
    val kind: AgentKind,
    val workspacePath: String? = null,
    val stableSessionId: String? = null,
    val epoch: Long? = null,
    val continuation: ContinuationMetadata? = null,
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
)

data class ContinuationMetadata(
    val reason: String? = null,
    val previousEpoch: Long? = null,
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

data class GitVerifyRequest(
    val repoUrl: String,
    val branch: String? = null,
)

data class GitVerifyResponse(
    val read: Boolean,
    val write: Boolean,
    val detail: String,
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
