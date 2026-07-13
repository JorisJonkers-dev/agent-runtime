package com.jorisjonkers.personalstack.agentgateway.tmux

interface AgentSessionSpawnOperations {
    fun spawn(
        kind: AgentKind,
        workspacePath: String? = null,
        stableSessionId: String? = null,
        epoch: Long? = null,
        continuation: AgentContinuation? = null,
    ): AgentSession

    fun spawn(
        kind: AgentKind,
        workspacePath: String? = null,
        resumeCliSessionId: String,
    ): AgentSession

    fun spawn(request: AgentSpawnRequest): AgentSession
}

interface AgentSessionLifecycleOperations {
    fun stop(id: String): Boolean
}

interface AgentSessionRegistryOperations {
    fun get(id: String): AgentSession?

    fun list(): List<AgentSession>

    fun idleMillis(id: String): Long?
}

interface AgentSessionControlOperations {
    fun send(
        id: String,
        input: String,
        enter: Boolean = true,
    )

    fun stageInput(
        id: String,
        content: String,
        requestedName: String?,
    ): StagedInput

    fun capture(
        id: String,
        historyLines: Int = 1_000,
    ): String

    fun captureWithEscapes(id: String): String

    fun resize(
        id: String,
        cols: Int,
        rows: Int,
    )
}

interface AgentTranscriptAdminOperations {
    fun cleanupTranscript(stableSessionId: String): Boolean
}
