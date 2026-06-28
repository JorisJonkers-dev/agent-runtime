package com.jorisjonkers.personalstack.agentgateway.tmux

import tools.jackson.databind.ObjectMapper
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.Path

class ClaudeTranscriptLocator(
    private val projectsDir: Path = defaultClaudeProjectsDir(),
    private val objectMapper: ObjectMapper = ObjectMapper(),
) {
    data class Transcript(
        val path: Path,
        val cwd: String,
    )

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
    ): Boolean = findTranscript(cwd, sessionId) != null

    @Suppress("ReturnCount")
    fun findTranscript(
        cwd: String,
        sessionId: String,
    ): Transcript? {
        val normalizedCwd = normalizeCwd(cwd)
        val exact = transcriptPath(normalizedCwd, sessionId)
        if (Files.isRegularFile(exact)) {
            return Transcript(exact, transcriptCwd(exact) ?: normalizedCwd)
        }
        if (!Files.isDirectory(projectsDir)) return null
        return scanForTranscript(sessionId)
    }

    // The original launch cwd is not always replayed on revival, so when the
    // exact project dir misses, scan every persisted Claude project dir for a
    // transcript with this id and recover the cwd Claude itself recorded.
    private fun scanForTranscript(sessionId: String): Transcript? =
        Files
            .list(projectsDir)
            .use { projects ->
                projects
                    .filter(Files::isDirectory)
                    .map { it.resolve("$sessionId.jsonl") }
                    .filter(Files::isRegularFile)
                    .toList()
            }.firstNotNullOfOrNull { transcript ->
                transcriptCwd(transcript)?.let { Transcript(transcript, normalizeCwd(it)) }
            }

    private fun encodeProjectPath(cwd: String): String =
        normalizeCwd(cwd)
            .replace('/', '-')
            .replace('.', '-')

    private fun normalizeCwd(cwd: String): String =
        Path(cwd)
            .toAbsolutePath()
            .normalize()
            .toString()

    private fun transcriptCwd(path: Path): String? =
        Files
            .newBufferedReader(path)
            .useLines { lines ->
                lines.firstNotNullOfOrNull(::lineCwd)
            }

    private fun lineCwd(line: String): String? =
        runCatching {
            objectMapper
                .readTree(line)
                .get("cwd")
                ?.asString()
                ?.takeIf(String::isNotBlank)
        }.getOrNull()

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
