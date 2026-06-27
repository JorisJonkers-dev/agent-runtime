package com.jorisjonkers.personalstack.agentgateway.tmux

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.Path

class ClaudeTranscriptLocator(
    private val projectsDir: Path = defaultClaudeProjectsDir(),
) {
    fun transcriptPath(
        cwd: String,
        sessionId: String,
    ): Path =
        projectsDir
            .resolve(encodeProjectPath(cwd))
            .resolve("$sessionId.jsonl")

    fun transcriptExists(
        cwd: String,
        sessionId: String,
    ): Boolean = Files.exists(transcriptPath(cwd, sessionId))

    private fun encodeProjectPath(cwd: String): String =
        Path(cwd)
            .toAbsolutePath()
            .normalize()
            .toString()
            .replace('/', '-')
            .replace('.', '-')

    companion object {
        fun fromEnvironment(env: Map<String, String> = System.getenv()): ClaudeTranscriptLocator =
            ClaudeTranscriptLocator(defaultClaudeProjectsDir(env))
    }
}

private fun defaultClaudeProjectsDir(env: Map<String, String> = System.getenv()): Path {
    val configDir =
        env["CLAUDE_CONFIG_DIR"]
            ?.takeIf(String::isNotBlank)
            ?.let(::Path)
            ?: Path(env["HOME"]?.takeIf(String::isNotBlank) ?: System.getProperty("user.home")).resolve(".claude")
    return configDir.resolve("projects")
}
