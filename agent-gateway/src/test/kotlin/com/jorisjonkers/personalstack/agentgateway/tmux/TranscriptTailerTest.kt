@file:Suppress("UnusedParameter")

package com.jorisjonkers.personalstack.agentgateway.tmux

import com.jorisjonkers.personalstack.agentgateway.config.GatewayProperties
import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.concurrent.ScheduledExecutorService

class TranscriptTailerTest {
    @Test
    fun `replayAvailable reports replayed bytes and frames`(
        @TempDir tmp: Path,
    ) {
        val stable = "11111111-1111-1111-1111-111111111111"
        val store = transcriptStore(tmp)
        store.open(stable, 1)
        Files.writeString(store.activeSegmentPath(stable), "abcdef", StandardOpenOption.APPEND)
        store.recoverMetadata(stable)
        val frames = mutableListOf<TranscriptTextFrame>()

        val result =
            TranscriptTailer(store, stable, startOffset = 0, maxChunkChars = 2, onText = frames::add)
                .use { it.replayAvailable() }

        assertThat(result).isEqualTo(TranscriptReplayResult(bytes = 6, frames = 3, success = true))
        assertThat(frames.map { it.output }).containsExactly("ab", "cd", "ef")
        assertThat(frames.map { it.off }).containsExactly(2L, 4L, 6L)
    }

    @Test
    fun `replayAvailable reports bounded failure reason`(
        @TempDir tmp: Path,
    ) {
        val stable = "11111111-1111-1111-1111-111111111111"
        val store = mockk<TranscriptStore>()
        every { store.readRaw(stable, any(), any()) } throws IllegalStateException("x".repeat(400))

        val result =
            TranscriptTailer(store, stable, startOffset = 0, onText = {})
                .use { it.replayAvailable() }

        assertThat(result.success).isFalse
        assertThat(result.bytes).isEqualTo(0)
        assertThat(result.frames).isEqualTo(0)
        val reason = requireNotNull(result.failureReason)
        assertThat(reason).contains("IllegalStateException")
        assertThat(reason.length).isLessThanOrEqualTo(160)
    }

    @Test
    fun `close terminates live tail executor`(
        @TempDir tmp: Path,
    ) {
        val stable = "11111111-1111-1111-1111-111111111111"
        val store = transcriptStore(tmp)
        store.open(stable, 1)
        val tailer = TranscriptTailer(store, stable, startOffset = 0, intervalMs = 20, onText = {})

        tailer.start()
        tailer.close()

        val executor =
            TranscriptTailer::class.java
                .getDeclaredField("executor")
                .apply { isAccessible = true }
                .get(tailer) as ScheduledExecutorService
        assertThat(executor.isShutdown).isTrue
        assertThat(executor.isTerminated).isTrue
    }

    @Test
    fun `start reports startup success`(
        @TempDir tmp: Path,
    ) {
        val stable = "11111111-1111-1111-1111-111111111111"
        val store = transcriptStore(tmp)
        store.open(stable, 1)
        val starts = mutableListOf<TranscriptTailerStartResult>()
        val tailer =
            TranscriptTailer(store, stable, startOffset = 0, intervalMs = 20, onStart = starts::add, onText = {})

        val result = tailer.start()
        tailer.close()

        assertThat(result).isEqualTo(TranscriptTailerStartResult(success = true))
        assertThat(starts).containsExactly(TranscriptTailerStartResult(success = true))
    }

    @Test
    fun `start reports bounded startup failure`(
        @TempDir tmp: Path,
    ) {
        val stable = "11111111-1111-1111-1111-111111111111"
        val store = transcriptStore(tmp)
        store.open(stable, 1)
        val starts = mutableListOf<TranscriptTailerStartResult>()
        val tailer =
            TranscriptTailer(store, stable, startOffset = 0, intervalMs = 0, onStart = starts::add, onText = {})

        val result = tailer.start()
        tailer.close()

        assertThat(result.success).isFalse
        assertThat(result.failureReason).contains("IllegalArgumentException")
        assertThat(result.failureReason!!.length).isLessThanOrEqualTo(160)
        assertThat(starts).containsExactly(result)
    }

    private fun transcriptStore(tmp: Path): TranscriptStore =
        TranscriptStore(
            GatewayProperties(
                workspaceRoot = tmp.toString(),
                tmux = GatewayProperties.Tmux(socketName = "agent-gw", stateDir = tmp.resolve("tmux").toString()),
                cli = GatewayProperties.Cli(claude = "claude", codex = "codex"),
                git = GatewayProperties.Git(deployKeyDir = "/x"),
            ),
        )
}
