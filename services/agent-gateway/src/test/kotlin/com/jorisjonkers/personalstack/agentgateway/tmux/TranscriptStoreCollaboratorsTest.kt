package com.jorisjonkers.personalstack.agentgateway.tmux

import com.jorisjonkers.personalstack.agentgateway.config.GatewayProperties
import com.jorisjonkers.personalstack.agentgateway.observability.AgentGatewayTelemetry
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

class TranscriptStoreCollaboratorsTest {
    private fun context(
        tmp: Path,
        segmentBytes: Long = 8,
        capBytes: Long = 16,
        leaseTtlSeconds: Long = 60,
        clock: Clock = Clock.fixed(Instant.parse("2026-06-12T09:00:00Z"), ZoneOffset.UTC),
    ): TranscriptStoreContext =
        TranscriptStoreContext(
            GatewayProperties(
                workspaceRoot = tmp.toString(),
                tmux = GatewayProperties.Tmux(socketName = "agent-gw", stateDir = tmp.resolve("tmux").toString()),
                cli = GatewayProperties.Cli(claude = "claude", codex = "codex"),
                transcripts =
                    GatewayProperties.Transcripts(
                        segmentBytes = segmentBytes,
                        capBytes = capBytes,
                        leaseTtlSeconds = leaseTtlSeconds,
                    ),
            ),
            clock,
            telemetry = AgentGatewayTelemetry.NOOP,
        )

    @Test
    fun `lease store rejects a competing live owner`(
        @TempDir tmp: Path,
    ) {
        val leaseStore = TranscriptLeaseStore(context(tmp, leaseTtlSeconds = 30))
        val stable = "11111111-1111-1111-1111-111111111111"

        val lease = leaseStore.acquire(stable, "owner-a", 1)

        assertThat(lease.owner).isEqualTo("owner-a")
        assertThatThrownBy { leaseStore.acquire(stable, "owner-b", 1) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("leased by owner-a")
    }

    @Test
    fun `metadata store rebuilds logical end from segment files`(
        @TempDir tmp: Path,
    ) {
        val context = context(tmp)
        val metadataStore = TranscriptMetadataStore(context)
        val stable = "11111111-1111-1111-1111-111111111111"
        metadataStore.open(stable, 7)
        Files.writeString(context.paths.segmentPath(stable, 0), "hello", StandardOpenOption.APPEND)

        val metadata = metadataStore.recover(stable)

        assertThat(metadata.epoch).isEqualTo(7)
        assertThat(metadata.logicalStart).isZero()
        assertThat(metadata.logicalEnd).isEqualTo(5)
    }

    @Test
    fun `segment store reads across retained segments after trim`(
        @TempDir tmp: Path,
    ) {
        val context = context(tmp, segmentBytes = 4, capBytes = 8)
        val metadataStore = TranscriptMetadataStore(context)
        val segmentStore = TranscriptSegmentStore(context, metadataStore)
        val stable = "11111111-1111-1111-1111-111111111111"
        metadataStore.open(stable, 1)
        Files.writeString(segmentStore.activeSegmentPath(stable), "1111", StandardOpenOption.APPEND)
        segmentStore.rotateIfNeeded(stable)
        Files.writeString(segmentStore.activeSegmentPath(stable), "2222", StandardOpenOption.APPEND)
        segmentStore.rotateIfNeeded(stable)
        Files.writeString(segmentStore.activeSegmentPath(stable), "3333", StandardOpenOption.APPEND)

        val metadata = segmentStore.trimIfNeeded(stable)
        val read = segmentStore.readRaw(stable, 0)

        assertThat(metadata.logicalStart).isEqualTo(4)
        assertThat(read.startOffset).isEqualTo(4)
        assertThat(String(read.bytes, Charsets.UTF_8)).isEqualTo("22223333")
    }

    @Test
    fun `storage stats store counts only direct uuid segment files`(
        @TempDir tmp: Path,
    ) {
        val context = context(tmp)
        val statsStore = TranscriptStorageStatsStore(context)
        val root = context.root()
        val stable = "11111111-1111-1111-1111-111111111111"
        val segments = root.resolve(stable).resolve("segments")
        Files.createDirectories(segments.resolve("nested"))
        Files.writeString(segments.resolve("segment-000000.log"), "ok")
        Files.writeString(segments.resolve("nested").resolve("segment-000001.log"), "nested")
        Files.createDirectories(root.resolve("not-a-session").resolve("segments"))
        Files.writeString(root.resolve("not-a-session").resolve("segments").resolve("segment-000000.log"), "ignored")

        val stats = statsStore.refresh()

        assertThat(stats.usedBytes).isEqualTo(2)
        assertThat(statsStore.current()).isEqualTo(stats)
    }
}
