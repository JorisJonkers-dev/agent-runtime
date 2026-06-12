package com.jorisjonkers.personalstack.agentgateway.tmux

import com.jorisjonkers.personalstack.agentgateway.config.GatewayProperties
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList

class LogTailerTest {
    @Test
    fun `tailer defaults to a low-latency 40ms poll interval`(
        @TempDir tmp: Path,
    ) {
        val tailer = LogTailer(tmp.resolve("agent.log")) { }
        val field = LogTailer::class.java.getDeclaredField("intervalMs").apply { isAccessible = true }
        assertThat(field.getLong(tailer)).isEqualTo(40L)
    }

    @Test
    fun `tailer emits text appended to the file`(
        @TempDir tmp: Path,
    ) {
        val file = tmp.resolve("agent.log")
        Files.createFile(file)
        val received = CopyOnWriteArrayList<String>()
        LogTailer(file, intervalMs = 50) { received.add(it) }.use { tailer ->
            tailer.start()
            Files.writeString(file, "hello ", StandardOpenOption.APPEND)
            Files.writeString(file, "world\n", StandardOpenOption.APPEND)
            await().atMost(Duration.ofSeconds(2)).until {
                received.joinToString("").contains("hello world")
            }
            assertThat(received.joinToString("")).contains("hello world")
        }
    }

    @Test
    fun `tailer starts at EOF and ignores bytes written before start`(
        @TempDir tmp: Path,
    ) {
        val file = tmp.resolve("agent.log")
        Files.writeString(file, "OLD HISTORY THAT MUST NOT REPLAY\n")
        val received = CopyOnWriteArrayList<String>()
        LogTailer(file, intervalMs = 50) { received.add(it) }.use { tailer ->
            tailer.start()
            Files.writeString(file, "fresh bytes\n", StandardOpenOption.APPEND)
            await().atMost(Duration.ofSeconds(2)).until {
                received.joinToString("").contains("fresh bytes")
            }
            assertThat(received.joinToString("")).contains("fresh bytes")
            assertThat(received.joinToString("")).doesNotContain("OLD HISTORY")
        }
    }

    @Test
    fun `completeUtf8Length holds back a codepoint split across a read`() {
        // "Aé": 0x41 then 0xC3 0xA9 — the whole buffer is complete.
        assertThat(LogTailer.completeUtf8Length(byteArrayOf(0x41, 0xC3.toByte(), 0xA9.toByte()))).isEqualTo(3)
        // Same "é" missing its continuation byte: carry from the lead at index 1.
        assertThat(LogTailer.completeUtf8Length(byteArrayOf(0x41, 0xC3.toByte()))).isEqualTo(1)
        // Box-drawing "─" (E2 94 80) missing its last byte: nothing is ready yet.
        assertThat(LogTailer.completeUtf8Length(byteArrayOf(0xE2.toByte(), 0x94.toByte()))).isEqualTo(0)
    }

    @Test
    fun `chunked bounds pieces and never splits a surrogate pair`() {
        val text = "a😀b" // the emoji is U+1F600, one codepoint as a surrogate pair
        val out = mutableListOf<String>()
        LogTailer.chunked(text, 2, out::add)
        assertThat(out.joinToString("")).isEqualTo(text)
        assertThat(out).allSatisfy { assertThat(it.length).isLessThanOrEqualTo(2) }
        out.filter { it.isNotEmpty() }.forEach {
            assertThat(Character.isLowSurrogate(it.first())).isFalse()
            assertThat(Character.isHighSurrogate(it.last())).isFalse()
        }
    }

    @Test
    fun `streams a multibyte char even when it is split across two reads`(
        @TempDir tmp: Path,
    ) {
        val file = tmp.resolve("split.log")
        Files.createFile(file)
        val received = CopyOnWriteArrayList<String>()
        LogTailer(file, intervalMs = 20) { received.add(it) }.use { tailer ->
            tailer.start()
            // "─" is E2 94 80; deliver the lead two bytes, then the rest + 'A'.
            // A poll may land between the writes (exercising the carry) or
            // after both; either way the decoded result must be intact.
            Files.write(file, byteArrayOf(0xE2.toByte(), 0x94.toByte()), StandardOpenOption.APPEND)
            Thread.sleep(40)
            Files.write(file, byteArrayOf(0x80.toByte(), 0x41), StandardOpenOption.APPEND)
            await().atMost(Duration.ofSeconds(2)).until { received.joinToString("") == "─A" }
        }
    }

    @Test
    fun `restarts from the new beginning after the log is truncated to its cap`(
        @TempDir tmp: Path,
    ) {
        val file = tmp.resolve("capped.log")
        Files.writeString(file, "old-content")
        val received = CopyOnWriteArrayList<String>()
        LogTailer(file, intervalMs = 20) { received.add(it) }.use { tailer ->
            tailer.start()
            Files.writeString(file, "-more", StandardOpenOption.APPEND)
            await().atMost(Duration.ofSeconds(2)).until { received.joinToString("") == "-more" }
            FileChannel.open(file, StandardOpenOption.WRITE).use { it.truncate(0) }
            Files.writeString(file, "fresh", StandardOpenOption.APPEND)
            await().atMost(Duration.ofSeconds(2)).until { received.joinToString("") == "-morefresh" }
        }
    }

    @Test
    fun `transcript tailer emits replay frames with byte accurate offsets`(
        @TempDir tmp: Path,
    ) {
        val stable = "11111111-1111-1111-1111-111111111111"
        val store = transcriptStore(tmp)
        store.open(stable, 1)
        Files.writeString(store.activeSegmentPath(stable), "abéZ", StandardOpenOption.APPEND)
        store.recoverMetadata(stable)
        val frames = CopyOnWriteArrayList<TranscriptTextFrame>()

        TranscriptTailer(store, stable, startOffset = 2, intervalMs = 20, maxChunkChars = 2, onText = frames::add)
            .use { it.replayAvailable() }

        assertThat(frames.map { it.output }).containsExactly("éZ")
        assertThat(frames.map { it.off }).containsExactly(5L)
    }

    @Test
    fun `transcript tailer carries split utf8 bytes across polls`(
        @TempDir tmp: Path,
    ) {
        val stable = "11111111-1111-1111-1111-111111111111"
        val store = transcriptStore(tmp)
        store.open(stable, 1)
        val received = CopyOnWriteArrayList<TranscriptTextFrame>()
        TranscriptTailer(store, stable, startOffset = 0, intervalMs = 20, onText = received::add).use { tailer ->
            tailer.start()
            Files.write(
                store.activeSegmentPath(stable),
                byteArrayOf(0xE2.toByte(), 0x94.toByte()),
                StandardOpenOption.APPEND,
            )
            Thread.sleep(40)
            Files.write(store.activeSegmentPath(stable), byteArrayOf(0x80.toByte(), 0x41), StandardOpenOption.APPEND)
            await().atMost(Duration.ofSeconds(2)).until { received.joinToString("") { it.output } == "─A" }
            assertThat(received.last().off).isEqualTo(4)
        }
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
