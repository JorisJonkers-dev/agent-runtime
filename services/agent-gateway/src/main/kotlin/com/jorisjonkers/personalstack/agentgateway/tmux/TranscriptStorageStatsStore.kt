package com.jorisjonkers.personalstack.agentgateway.tmux

import com.jorisjonkers.personalstack.agentgateway.observability.GatewayModeLabel
import com.jorisjonkers.personalstack.agentgateway.observability.GatewayStorageObjectLabel
import com.jorisjonkers.personalstack.agentgateway.observability.GatewayStorageTelemetrySample
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.time.Instant

internal class TranscriptStorageStatsStore(
    private val context: TranscriptStoreContext,
) {
    @Volatile
    private var cached =
        TranscriptStorageStats(
            usedBytes = 0,
            capBytes = context.props.transcripts.capBytes,
            refreshedAt = Instant.EPOCH,
        )

    fun current(): TranscriptStorageStats = cached

    fun refresh(): TranscriptStorageStats {
        val stats = scan()
        cached = stats
        context.telemetry.recordStorage(
            GatewayStorageTelemetrySample(
                storageObject = GatewayStorageObjectLabel.TRANSCRIPT,
                mode = GatewayModeLabel.DURABLE,
                bytes = stats.usedBytes,
            ),
        )
        context.telemetry.recordStorageLimit(
            GatewayStorageTelemetrySample(
                storageObject = GatewayStorageObjectLabel.TRANSCRIPT,
                mode = GatewayModeLabel.DURABLE,
                bytes = stats.capBytes,
            ),
        )
        return stats
    }

    private fun scan(): TranscriptStorageStats {
        val transcriptRoot = context.root()
        if (!Files.isDirectory(transcriptRoot, LinkOption.NOFOLLOW_LINKS)) {
            return stats(0)
        }
        val usedBytes =
            Files.list(transcriptRoot).use { paths ->
                paths
                    .filter { TranscriptStorePaths.UUID_SESSION_DIR.matches(it.fileName.toString()) }
                    .filter { safeDirectoryInside(transcriptRoot, it) }
                    .mapToLong { sessionStorageBytes(transcriptRoot, it) }
                    .sum()
            }
        return stats(usedBytes)
    }

    private fun sessionStorageBytes(
        transcriptRoot: Path,
        sessionDir: Path,
    ): Long {
        val segments = sessionDir.resolve("segments")
        if (!safeDirectoryInside(transcriptRoot, segments)) return 0
        return runCatching {
            Files.list(segments).use { paths ->
                paths
                    .filter { TranscriptStorePaths.SEGMENT_FILE.matches(it.fileName.toString()) }
                    .filter { safeRegularFileInside(transcriptRoot, it) }
                    .mapToLong { runCatching { Files.size(it) }.getOrDefault(0L) }
                    .sum()
            }
        }.getOrDefault(0L)
    }

    private fun stats(usedBytes: Long): TranscriptStorageStats =
        TranscriptStorageStats(
            usedBytes = usedBytes,
            capBytes = context.props.transcripts.capBytes,
            refreshedAt = Instant.now(context.clock),
        )

    private fun safeDirectoryInside(
        transcriptRoot: Path,
        path: Path,
    ): Boolean = pathInside(transcriptRoot, path) && Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)

    private fun safeRegularFileInside(
        transcriptRoot: Path,
        path: Path,
    ): Boolean = pathInside(transcriptRoot, path) && Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)

    private fun pathInside(
        transcriptRoot: Path,
        path: Path,
    ): Boolean {
        val normalizedRoot = transcriptRoot.toAbsolutePath().normalize()
        val normalizedPath = path.toAbsolutePath().normalize()
        if (!normalizedPath.startsWith(normalizedRoot)) return false
        return runCatching {
            path.toRealPath(LinkOption.NOFOLLOW_LINKS).startsWith(
                transcriptRoot.toRealPath(LinkOption.NOFOLLOW_LINKS),
            )
        }.getOrDefault(false)
    }
}
