package com.jorisjonkers.personalstack.agentgateway.headless

import com.jorisjonkers.personalstack.agentgateway.tmux.AgentKind
import java.nio.file.Path
import java.time.Instant

data class HeadlessJob(
    val id: String,
    val kind: AgentKind,
    val status: HeadlessJobStatus,
    val outputFile: Path,
    val exitCode: Int? = null,
    val createdAt: Instant,
    val completedAt: Instant? = null,
)
