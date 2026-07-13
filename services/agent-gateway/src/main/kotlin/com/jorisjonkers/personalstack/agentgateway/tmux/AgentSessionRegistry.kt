package com.jorisjonkers.personalstack.agentgateway.tmux

import java.nio.file.Files
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory registry of active agents on this Pod. ConcurrentHashMap is enough:
 * spawn-vs-stop on the same id is a caller conflict, not shared state recovery.
 */
internal class AgentSessionRegistry : AgentSessionRegistryOperations {
    private val sessions = ConcurrentHashMap<String, AgentSession>()

    override fun get(id: String): AgentSession? = sessions[id]

    override fun list(): List<AgentSession> = sessions.values.sortedBy { it.createdAt }

    override fun idleMillis(id: String): Long? {
        val session = sessions[id] ?: return null
        return runCatching {
            val lastWrite = Files.getLastModifiedTime(session.logFile).toMillis()
            (Instant.now().toEpochMilli() - lastWrite).coerceAtLeast(0L)
        }.getOrNull()
    }

    fun put(session: AgentSession) {
        sessions[session.id] = session
    }

    fun update(session: AgentSession) {
        sessions[session.id] = session
    }

    fun remove(id: String): AgentSession? = sessions.remove(id)

    fun values(): List<AgentSession> = sessions.values.toList()

    fun ids(): List<String> = sessions.keys.sorted()

    fun countsByKind(): Map<AgentKind, Int> = sessions.values.groupingBy { it.kind }.eachCount()
}
