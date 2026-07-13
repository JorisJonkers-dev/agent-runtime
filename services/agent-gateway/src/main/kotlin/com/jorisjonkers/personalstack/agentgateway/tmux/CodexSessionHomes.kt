package com.jorisjonkers.personalstack.agentgateway.tmux

import com.jorisjonkers.personalstack.agentgateway.config.GatewayProperties
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.time.Instant
import kotlin.io.path.Path

internal class CodexSessionHomes(
    private val props: GatewayProperties,
) {
    private val log = LoggerFactory.getLogger(CodexSessionHomes::class.java)

    fun homeFor(stableSessionId: String): Path =
        Path(props.codex.home).resolve(props.codex.sessionHomesSubdir).resolve(stableSessionId)

    fun prepare(home: Path) {
        runCatching {
            Files.createDirectories(home.resolve("sessions"))
            for (name in CODEX_SHARED_FILES) {
                val src = Path(props.codex.home).resolve(name)
                val link = home.resolve(name)
                if (Files.exists(link, LinkOption.NOFOLLOW_LINKS) || !Files.exists(src)) continue
                Files.createSymbolicLink(link, src)
            }
        }.onFailure { log.warn("could not prepare codex home {}: {}", home, it.message) }
    }

    fun captureSessionId(codexHome: Path): String? {
        val sessionsDir = codexHome.resolve("sessions")
        val deadline = Instant.now().plusMillis(props.codex.captureTimeoutMs)
        while (Instant.now().isBefore(deadline)) {
            newestRolloutId(sessionsDir)?.let { return it }
            try {
                Thread.sleep(props.codex.capturePollMs)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                break
            }
        }
        log.warn("codex session id not captured within {}ms under {}", props.codex.captureTimeoutMs, sessionsDir)
        return null
    }

    private fun newestRolloutId(sessionsDir: Path): String? {
        val newest =
            if (Files.isDirectory(sessionsDir)) {
                newestRolloutFile(sessionsDir)
            } else {
                null
            }
        return newest?.let { ROLLOUT_ID.find(it.fileName.toString())?.groupValues?.get(1) }
    }

    private fun newestRolloutFile(sessionsDir: Path): Path? =
        Files.walk(sessionsDir).use { stream ->
            stream
                .filter { Files.isRegularFile(it) && ROLLOUT_FILE.matches(it.fileName.toString()) }
                .max(compareBy { runCatching { Files.getLastModifiedTime(it).toMillis() }.getOrDefault(0L) })
                .orElse(null)
        }

    private companion object {
        val ROLLOUT_FILE = Regex("""^rollout-.*\.jsonl$""")
        val ROLLOUT_ID =
            Regex("""([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})\.jsonl$""")
        val CODEX_SHARED_FILES = listOf("auth.json", "config.toml")
    }
}
