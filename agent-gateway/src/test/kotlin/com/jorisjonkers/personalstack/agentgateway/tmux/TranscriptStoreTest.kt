package com.jorisjonkers.personalstack.agentgateway.tmux

import com.jorisjonkers.personalstack.agentgateway.config.GatewayProperties
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class TranscriptStoreTest {
    private fun store(
        tmp: Path,
        segmentBytes: Long = 8,
        capBytes: Long = 16,
        leaseTtlSeconds: Long = 60,
        retentionSeconds: Long = 0,
        clock: Clock = Clock.fixed(Instant.parse("2026-06-12T09:00:00Z"), ZoneOffset.UTC),
    ): TranscriptStore =
        TranscriptStore(
            GatewayProperties(
                workspaceRoot = tmp.toString(),
                tmux = GatewayProperties.Tmux(socketName = "agent-gw", stateDir = tmp.resolve("tmux").toString()),
                cli = GatewayProperties.Cli(claude = "claude", codex = "codex"),
                git = GatewayProperties.Git(deployKeyDir = "/x"),
                transcripts =
                    GatewayProperties.Transcripts(
                        segmentBytes = segmentBytes,
                        capBytes = capBytes,
                        leaseTtlSeconds = leaseTtlSeconds,
                        retentionSeconds = retentionSeconds,
                    ),
            ),
            clock,
        )

    @Test
    fun `active segment path lives under workspace transcript directory`(
        @TempDir tmp: Path,
    ) {
        val store = store(tmp)
        val stable = "11111111-1111-1111-1111-111111111111"

        store.open(stable, 1)
        val active = store.activeSegmentPath(stable)

        assertThat(active.toString()).startsWith(tmp.resolve(".agent-transcripts").resolve(stable).toString())
        assertThat(active.fileName.toString()).isEqualTo("segment-000000.log")
    }

    @Test
    fun `metadata end is rebuilt by scanning segment bytes`(
        @TempDir tmp: Path,
    ) {
        val store = store(tmp)
        val stable = "11111111-1111-1111-1111-111111111111"
        store.open(stable, 1)
        Files.writeString(store.activeSegmentPath(stable), "hello", StandardOpenOption.APPEND)

        val recovered = store.recoverMetadata(stable)

        assertThat(recovered.logicalStart).isEqualTo(0)
        assertThat(recovered.logicalEnd).isEqualTo(5)
    }

    @Test
    fun `rotation creates a new append-only active segment`(
        @TempDir tmp: Path,
    ) {
        val store = store(tmp, segmentBytes = 4)
        val stable = "11111111-1111-1111-1111-111111111111"
        store.open(stable, 1)
        Files.writeString(store.activeSegmentPath(stable), "1234", StandardOpenOption.APPEND)

        val metadata = store.rotateIfNeeded(stable)

        assertThat(metadata.activeSegment).isEqualTo(1)
        assertThat(store.activeSegmentPath(stable).fileName.toString()).isEqualTo("segment-000001.log")
    }

    @Test
    fun `front trim deletes only closed segments and advances logical start`(
        @TempDir tmp: Path,
    ) {
        val store = store(tmp, segmentBytes = 4, capBytes = 8)
        val stable = "11111111-1111-1111-1111-111111111111"
        store.open(stable, 1)
        Files.writeString(store.activeSegmentPath(stable), "1111", StandardOpenOption.APPEND)
        store.rotateIfNeeded(stable)
        Files.writeString(store.activeSegmentPath(stable), "2222", StandardOpenOption.APPEND)
        store.rotateIfNeeded(stable)
        Files.writeString(store.activeSegmentPath(stable), "3333", StandardOpenOption.APPEND)

        val trimmed = store.trimIfNeeded(stable)

        assertThat(trimmed.logicalStart).isEqualTo(4)
        assertThat(trimmed.logicalEnd).isEqualTo(12)
        assertThat(store.readRaw(stable, 0).startOffset).isEqualTo(4)
        assertThat(String(store.readRaw(stable, 4).bytes, Charsets.UTF_8)).isEqualTo("22223333")
    }

    @Test
    fun `lease blocks a second live owner but stale owner can be recovered`(
        @TempDir tmp: Path,
    ) {
        val stable = "11111111-1111-1111-1111-111111111111"
        val early = Clock.fixed(Instant.parse("2026-06-12T09:00:00Z"), ZoneOffset.UTC)
        val late = Clock.fixed(Instant.parse("2026-06-12T09:02:00Z"), ZoneOffset.UTC)
        val first = store(tmp, leaseTtlSeconds = 30, clock = early)
        first.acquireLease(stable, "owner-a", 1)

        assertThatThrownBy { first.acquireLease(stable, "owner-b", 1) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("leased by owner-a")

        val recovered = store(tmp, leaseTtlSeconds = 30, clock = late).acquireLease(stable, "owner-b", 1)
        assertThat(recovered.owner).isEqualTo("owner-b")
    }

    @Test
    fun `lease renewal extends the same owner fence`(
        @TempDir tmp: Path,
    ) {
        val stable = "11111111-1111-1111-1111-111111111111"
        val firstClock = Clock.fixed(Instant.parse("2026-06-12T09:00:00Z"), ZoneOffset.UTC)
        val secondClock = Clock.fixed(Instant.parse("2026-06-12T09:00:20Z"), ZoneOffset.UTC)
        val lease = store(tmp, leaseTtlSeconds = 30, clock = firstClock).acquireLease(stable, "owner-a", 1)

        val renewed = store(tmp, leaseTtlSeconds = 30, clock = secondClock).renewLease(lease)

        assertThat(renewed).isNotNull
        assertThat(renewed!!.token).isEqualTo(lease.token)
        assertThat(renewed.expiresAtMillis).isGreaterThan(lease.expiresAtMillis)
    }

    @Test
    fun `cleanup removes sealed retained transcript by stable uuid`(
        @TempDir tmp: Path,
    ) {
        val store = store(tmp, retentionSeconds = 0)
        val stable = "11111111-1111-1111-1111-111111111111"
        store.open(stable, 1)
        val path = store.activeSegmentPath(stable)
        Files.writeString(path, "bytes", StandardOpenOption.APPEND)
        store.seal(stable)

        assertThat(store.cleanup(stable)).isTrue
        assertThat(Files.exists(path)).isFalse
    }

    @Test
    fun `continuation delimiter marks ordinary restart once per epoch`(
        @TempDir tmp: Path,
    ) {
        val store = store(tmp)
        val stable = "11111111-1111-1111-1111-111111111111"
        store.open(stable, 2)

        store.appendContinuationDelimiter(stable, 2, AgentContinuation(reason = "restart", previousEpoch = 1))
        store.appendContinuationDelimiter(stable, 2, AgentContinuation(reason = "restart", previousEpoch = 1))

        val text = Files.readString(store.activeSegmentPath(stable))
        assertThat(Regex("continuation epoch=2").findAll(text).toList()).hasSize(1)
        assertThat(Regex("agent restarted").findAll(text).toList()).hasSize(1)
        assertThat(text).doesNotContain("updated setup")
        assertThat(text).doesNotContain("setup transition")
    }

    @Test
    fun `continuation delimiter does not infer setup transition from reason without labels`(
        @TempDir tmp: Path,
    ) {
        val store = store(tmp)
        val stable = "11111111-1111-1111-1111-111111111111"
        store.open(stable, 2)

        store.appendContinuationDelimiter(
            stable,
            2,
            AgentContinuation(
                reason = "setup-transition",
                previousEpoch = 1,
            ),
        )

        val text = Files.readString(store.activeSegmentPath(stable))
        assertThat(text).contains("agent restarted")
        assertThat(text).doesNotContain("setup transition")
    }

    @Test
    fun `continuation delimiter includes redacted setup transition labels`(
        @TempDir tmp: Path,
    ) {
        val store = store(tmp)
        val stable = "11111111-1111-1111-1111-111111111111"
        store.open(stable, 2)

        store.appendContinuationDelimiter(
            stable,
            2,
            AgentContinuation(
                reason = "restart secret=supersecret",
                previousEpoch = 1,
                fromSetupLabel = "Default runner token=ghp_1234567890123456",
                toSetupLabel = "GPU runner",
            ),
        )
        store.appendContinuationDelimiter(
            stable,
            2,
            AgentContinuation(
                reason = "restart secret=supersecret",
                previousEpoch = 1,
                fromSetupLabel = "Default runner token=ghp_1234567890123456",
                toSetupLabel = "GPU runner",
            ),
        )

        val text = Files.readString(store.activeSegmentPath(stable))
        assertThat(Regex("continuation epoch=2").findAll(text).toList()).hasSize(1)
        assertThat(text).contains("setup transition")
        assertThat(text).contains("fromSetup=\"Default runner token=[redacted]\"")
        assertThat(text).contains("toSetup=\"GPU runner\"")
        assertThat(text).contains("reason=restart secret=[redacted]")
        assertThat(text).doesNotContain("ghp_1234567890123456")
        assertThat(text).doesNotContain("supersecret")
    }
}
