package com.jorisjonkers.personalstack.agentgateway.tmux

import com.jorisjonkers.personalstack.agentgateway.config.GatewayProperties
import org.slf4j.LoggerFactory
import java.nio.file.Path
import java.util.UUID

internal class AgentCommandFactory(
    private val props: GatewayProperties,
    private val claudeTranscriptLocator: ClaudeTranscriptLocator,
) {
    private val log = LoggerFactory.getLogger(AgentCommandFactory::class.java)

    /**
     * Build the CLI command and return the native session id alongside it.
     *
     * Claude gets an explicit `--session-id` on fresh starts and `--resume`
     * only when the matching local transcript still exists. Codex runs under
     * an isolated CODEX_HOME so fresh rollout capture has no cross-session
     * ambiguity; shell has no native session id.
     */
    fun commandAndSessionIdFor(
        kind: AgentKind,
        cwd: String,
        resumeCliSessionId: String? = null,
        codexHome: Path? = null,
    ): AgentCommand =
        when (kind) {
            AgentKind.CLAUDE -> claudeCommand(cwd, resumeCliSessionId)
            AgentKind.CODEX -> codexCommand(cwd, resumeCliSessionId, codexHome)
            AgentKind.SHELL -> AgentCommand(command = listOf("/bin/bash", "-l"), cliSessionId = null, cwd = cwd)
        }

    private fun claudeCommand(
        cwd: String,
        resumeCliSessionId: String?,
    ): AgentCommand {
        val cliSessionId = resumeCliSessionId ?: UUID.randomUUID().toString()
        val transcript = resumeCliSessionId?.let { claudeTranscriptLocator.findTranscript(cwd, it) }
        val sessionArgs =
            if (resumeCliSessionId == null) {
                listOf("--session-id", cliSessionId)
            } else if (transcript != null) {
                log.info(
                    "claude revival selected resume sessionId={} reason=transcript-exists " +
                        "requestedCwd={} transcriptCwd={}",
                    resumeCliSessionId,
                    cwd,
                    transcript.cwd,
                )
                listOf("--resume", resumeCliSessionId)
            } else {
                log.info(
                    "claude revival selected fresh-with-stable-id sessionId={} reason=transcript-missing",
                    resumeCliSessionId,
                )
                listOf("--session-id", resumeCliSessionId)
            }
        return AgentCommand(
            command = listOf(props.cli.claude) + props.cli.claudeArgs + sessionArgs,
            cliSessionId = cliSessionId,
            cwd = transcript?.cwd ?: cwd,
        )
    }

    private fun codexCommand(
        cwd: String,
        resumeCliSessionId: String?,
        codexHome: Path?,
    ): AgentCommand {
        val envPrefix = codexHome?.let { listOf("env", "CODEX_HOME=$it") }.orEmpty()
        val resumeArgs = resumeCliSessionId?.let { listOf("resume", it) }.orEmpty()
        return AgentCommand(
            command = envPrefix + listOf(props.cli.codex) + props.cli.codexArgs + resumeArgs,
            cliSessionId = resumeCliSessionId,
            cwd = cwd,
        )
    }
}

internal data class AgentCommand(
    val command: List<String>,
    val cliSessionId: String?,
    val cwd: String,
)
