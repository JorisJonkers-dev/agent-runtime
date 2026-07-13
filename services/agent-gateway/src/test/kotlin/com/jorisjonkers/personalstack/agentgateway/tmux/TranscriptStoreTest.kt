package com.jorisjonkers.personalstack.agentgateway.tmux

import com.jorisjonkers.personalstack.agentgateway.config.GatewayProperties
import com.jorisjonkers.personalstack.agentgateway.observability.AgentGatewayTelemetry
import com.jorisjonkers.personalstack.agentgateway.observability.MicrometerAgentGatewayTelemetry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Assumptions.assumeTrue
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
        options: StoreOptions = StoreOptions(),
    ): TranscriptStore =
        TranscriptStore(
            GatewayProperties(
                workspaceRoot = tmp.toString(),
                tmux = GatewayProperties.Tmux(socketName = "agent-gw", stateDir = tmp.resolve("tmux").toString()),
                cli = GatewayProperties.Cli(claude = "claude", codex = "codex"),
                transcripts =
                    GatewayProperties.Transcripts(
                        segmentBytes = options.segmentBytes,
                        capBytes = options.capBytes,
                        leaseTtlSeconds = options.leaseTtlSeconds,
                        retentionSeconds = options.retentionSeconds,
                    ),
            ),
            options.clock,
            options.telemetry,
        )

    private data class StoreOptions(
        val segmentBytes: Long = 8,
        val capBytes: Long = 16,
        val leaseTtlSeconds: Long = 60,
        val retentionSeconds: Long = 0,
        val clock: Clock = Clock.fixed(Instant.parse("2026-06-12T09:00:00Z"), ZoneOffset.UTC),
        val telemetry: AgentGatewayTelemetry = AgentGatewayTelemetry.NOOP,
    )

    @Test
    fun `active segment path lives under workspace transcript directory`(
        @TempDir tmp: Path,
    ) {
        val store = store(tmp)
        val stable = "11111111-1111-1111-1111-111111111111"

        store.open(stable, 1)
        val active = store.segmentStore.activeSegmentPath(stable)

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
        Files.writeString(store.segmentStore.activeSegmentPath(stable), "hello", StandardOpenOption.APPEND)

        val recovered = store.recoverMetadata(stable)

        assertThat(recovered.logicalStart).isEqualTo(0)
        assertThat(recovered.logicalEnd).isEqualTo(5)
    }

    @Test
    fun `rotation creates a new append-only active segment`(
        @TempDir tmp: Path,
    ) {
        val store = store(tmp, StoreOptions(segmentBytes = 4))
        val stable = "11111111-1111-1111-1111-111111111111"
        store.open(stable, 1)
        Files.writeString(store.segmentStore.activeSegmentPath(stable), "1234", StandardOpenOption.APPEND)

        val metadata = store.rotateIfNeeded(stable)

        assertThat(metadata.activeSegment).isEqualTo(1)
        assertThat(store.segmentStore.activeSegmentPath(stable).fileName.toString()).isEqualTo("segment-000001.log")
    }

    @Test
    fun `front trim deletes only closed segments and advances logical start`(
        @TempDir tmp: Path,
    ) {
        val store = store(tmp, StoreOptions(segmentBytes = 4, capBytes = 8))
        val stable = "11111111-1111-1111-1111-111111111111"
        store.open(stable, 1)
        Files.writeString(store.segmentStore.activeSegmentPath(stable), "1111", StandardOpenOption.APPEND)
        store.rotateIfNeeded(stable)
        Files.writeString(store.segmentStore.activeSegmentPath(stable), "2222", StandardOpenOption.APPEND)
        store.rotateIfNeeded(stable)
        Files.writeString(store.segmentStore.activeSegmentPath(stable), "3333", StandardOpenOption.APPEND)

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
        val first = store(tmp, StoreOptions(leaseTtlSeconds = 30, clock = early))
        first.acquireLease(stable, "owner-a", 1)

        assertThatThrownBy { first.acquireLease(stable, "owner-b", 1) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("leased by owner-a")

        val recovered = store(tmp, StoreOptions(leaseTtlSeconds = 30, clock = late)).acquireLease(stable, "owner-b", 1)
        assertThat(recovered.owner).isEqualTo("owner-b")
    }

    @Test
    fun `lease renewal extends the same owner fence`(
        @TempDir tmp: Path,
    ) {
        val stable = "11111111-1111-1111-1111-111111111111"
        val firstClock = Clock.fixed(Instant.parse("2026-06-12T09:00:00Z"), ZoneOffset.UTC)
        val secondClock = Clock.fixed(Instant.parse("2026-06-12T09:00:20Z"), ZoneOffset.UTC)
        val lease =
            store(tmp, StoreOptions(leaseTtlSeconds = 30, clock = firstClock))
                .acquireLease(stable, "owner-a", 1)

        val renewed = store(tmp, StoreOptions(leaseTtlSeconds = 30, clock = secondClock)).leaseStore.renew(lease)

        assertThat(renewed).isNotNull
        assertThat(renewed!!.token).isEqualTo(lease.token)
        assertThat(renewed.expiresAtMillis).isGreaterThan(lease.expiresAtMillis)
    }

    @Test
    fun `cleanup removes sealed retained transcript by stable uuid`(
        @TempDir tmp: Path,
    ) {
        val store = store(tmp, StoreOptions(retentionSeconds = 0))
        val stable = "11111111-1111-1111-1111-111111111111"
        store.open(stable, 1)
        val path = store.segmentStore.activeSegmentPath(stable)
        Files.writeString(path, "bytes", StandardOpenOption.APPEND)
        store.seal(stable)

        assertThat(store.cleanup(stable)).isTrue
        assertThat(Files.exists(path)).isFalse
    }

    @Test
    fun `storage stats report zero usage and configured durable cap`(
        @TempDir tmp: Path,
    ) {
        val registry = SimpleMeterRegistry()
        val store = store(tmp, StoreOptions(capBytes = 123, telemetry = MicrometerAgentGatewayTelemetry(registry)))

        val stats = store.storageStatsStore.refresh()

        assertThat(stats.usedBytes).isZero()
        assertThat(stats.capBytes).isEqualTo(123)
        assertThat(store.storageStatsStore.current()).isEqualTo(stats)
        assertThat(registry.find("agent.gateway.storage.bytes").gauge()!!.value()).isZero()
        assertThat(registry.find("agent.gateway.storage.limit.bytes").gauge()!!.value()).isEqualTo(123.0)
    }

    @Test
    fun `storage stats are cached until maintenance refresh`(
        @TempDir tmp: Path,
    ) {
        val registry = SimpleMeterRegistry()
        val store = store(tmp, StoreOptions(telemetry = MicrometerAgentGatewayTelemetry(registry)))
        val stable = "11111111-1111-1111-1111-111111111111"
        store.open(stable, 1)
        Files.writeString(store.segmentStore.activeSegmentPath(stable), "one", StandardOpenOption.APPEND)

        val first = store.storageStatsStore.refresh()
        Files.writeString(store.segmentStore.activeSegmentPath(stable), "two", StandardOpenOption.APPEND)

        assertThat(store.storageStatsStore.current().usedBytes).isEqualTo(first.usedBytes)
        assertThat(registry.find("agent.gateway.storage.bytes").gauge()!!.value()).isEqualTo(first.usedBytes.toDouble())

        val refreshed = store.trimIfNeeded(stable)

        assertThat(refreshed.byteCount).isEqualTo(6)
        assertThat(store.storageStatsStore.current().usedBytes).isEqualTo(6)
        assertThat(registry.find("agent.gateway.storage.bytes").gauge()!!.value()).isEqualTo(6.0)
    }

    @Test
    fun `storage stats include inactive retained sessions`(
        @TempDir tmp: Path,
    ) {
        val store = store(tmp, StoreOptions(retentionSeconds = 3600))
        val active = "11111111-1111-1111-1111-111111111111"
        val retained = "22222222-2222-2222-2222-222222222222"
        store.open(active, 1)
        Files.writeString(store.segmentStore.activeSegmentPath(active), "active", StandardOpenOption.APPEND)
        store.open(retained, 1)
        Files.writeString(store.segmentStore.activeSegmentPath(retained), "retained", StandardOpenOption.APPEND)
        store.seal(retained)

        val stats = store.storageStatsStore.refresh()

        assertThat(stats.usedBytes).isEqualTo("activeretained".length.toLong())
    }

    @Test
    fun `storage scan ignores paths outside transcript root`(
        @TempDir tmp: Path,
    ) {
        val store = store(tmp)
        val root = store.root()
        val stable = "11111111-1111-1111-1111-111111111111"
        val segments = root.resolve(stable).resolve("segments")
        Files.createDirectories(segments)
        Files.writeString(
            segments.resolve("segment-000000.log"),
            "in",
            StandardOpenOption.CREATE_NEW,
            StandardOpenOption.WRITE,
        )
        val outside = tmp.resolve("outside.log")
        Files.writeString(outside, "outside", StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
        val link = segments.resolve("segment-000001.log")
        assumeTrue(runCatching { Files.createSymbolicLink(link, outside) }.isSuccess)

        val stats = store.storageStatsStore.refresh()

        assertThat(stats.usedBytes).isEqualTo(2)
    }

    @Test
    fun `storage scan is bounded to uuid session segment files`(
        @TempDir tmp: Path,
    ) {
        val store = store(tmp)
        val root = store.root()
        val stable = "11111111-1111-1111-1111-111111111111"
        val segments = root.resolve(stable).resolve("segments")
        Files.createDirectories(segments.resolve("nested"))
        Files.writeString(
            segments.resolve("segment-000000.log"),
            "ok",
            StandardOpenOption.CREATE_NEW,
            StandardOpenOption.WRITE,
        )
        Files.writeString(
            segments.resolve("nested").resolve("segment-000001.log"),
            "nested",
            StandardOpenOption.CREATE_NEW,
            StandardOpenOption.WRITE,
        )
        Files.writeString(
            segments.resolve("segment-latest.log"),
            "unknown",
            StandardOpenOption.CREATE_NEW,
            StandardOpenOption.WRITE,
        )
        Files.createDirectories(root.resolve("not-a-session").resolve("segments"))
        Files.writeString(
            root.resolve("not-a-session").resolve("segments").resolve("segment-000000.log"),
            "ignored",
            StandardOpenOption.CREATE_NEW,
            StandardOpenOption.WRITE,
        )

        val stats = store.storageStatsStore.refresh()

        assertThat(stats.usedBytes).isEqualTo(2)
    }

    @Test
    fun `storage gauges use aggregate labels only`(
        @TempDir tmp: Path,
    ) {
        val registry = SimpleMeterRegistry()
        val store = store(tmp, StoreOptions(telemetry = MicrometerAgentGatewayTelemetry(registry)))
        val stable = "11111111-1111-1111-1111-111111111111"
        store.open(stable, 1)
        Files.writeString(store.segmentStore.activeSegmentPath(stable), "bytes", StandardOpenOption.APPEND)

        store.seal(stable)

        val storageMeters =
            registry.meters
                .filter { it.id.name in setOf("agent.gateway.storage.bytes", "agent.gateway.storage.limit.bytes") }
        assertThat(storageMeters).hasSize(2)
        storageMeters.forEach { meter ->
            assertThat(meter.id.tags.map { it.key })
                .containsExactlyInAnyOrder("service", "storage_object", "mode")
            assertThat(meter.id.tags.map { it.value })
                .doesNotContain(stable, store.root().toString())
        }
    }

    @Test
    fun `continuation delimiter marks ordinary restart once per epoch`(
        @TempDir tmp: Path,
    ) {
        val store = store(tmp)
        val stable = "11111111-1111-1111-1111-111111111111"
        store.open(stable, 2)

        store.continuationStore.appendDelimiter(stable, 2, AgentContinuation(reason = "restart", previousEpoch = 1))
        store.continuationStore.appendDelimiter(stable, 2, AgentContinuation(reason = "restart", previousEpoch = 1))

        val text = Files.readString(store.segmentStore.activeSegmentPath(stable))
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

        store.continuationStore.appendDelimiter(
            stable,
            2,
            AgentContinuation(
                reason = "setup-transition",
                previousEpoch = 1,
            ),
        )

        val text = Files.readString(store.segmentStore.activeSegmentPath(stable))
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

        store.continuationStore.appendDelimiter(
            stable,
            2,
            AgentContinuation(
                reason = "restart secret=supersecret",
                previousEpoch = 1,
                fromSetupLabel = "Default runner token=ghp_1234567890123456",
                toSetupLabel = "GPU runner",
            ),
        )
        store.continuationStore.appendDelimiter(
            stable,
            2,
            AgentContinuation(
                reason = "restart secret=supersecret",
                previousEpoch = 1,
                fromSetupLabel = "Default runner token=ghp_1234567890123456",
                toSetupLabel = "GPU runner",
            ),
        )

        val text = Files.readString(store.segmentStore.activeSegmentPath(stable))
        assertThat(Regex("continuation epoch=2").findAll(text).toList()).hasSize(1)
        assertThat(text).contains("setup transition")
        assertThat(text).contains("fromSetup=\"Default runner token=[redacted]\"")
        assertThat(text).contains("toSetup=\"GPU runner\"")
        assertThat(text).contains("reason=restart secret=[redacted]")
        assertThat(text).doesNotContain("ghp_1234567890123456")
        assertThat(text).doesNotContain("supersecret")
    }
}
