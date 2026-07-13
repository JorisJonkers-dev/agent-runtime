package com.jorisjonkers.personalstack.agentgateway.tmux

import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator
import java.util.Locale
import kotlin.streams.toList

internal class TranscriptStorePaths(
    private val context: TranscriptStoreContext,
) {
    fun sessionDir(stableSessionId: String): Path = context.root().resolve(stableSessionId)

    fun segmentsDir(stableSessionId: String): Path = sessionDir(stableSessionId).resolve("segments")

    fun metadataFile(stableSessionId: String): Path = sessionDir(stableSessionId).resolve("metadata.properties")

    fun leaseFile(stableSessionId: String): Path = sessionDir(stableSessionId).resolve("lease.properties")

    fun segmentPath(
        stableSessionId: String,
        index: Int,
    ): Path = segmentsDir(stableSessionId).resolve("segment-%06d.log".format(Locale.ROOT, index))

    fun segmentFiles(stableSessionId: String): List<Path> {
        val dir = segmentsDir(stableSessionId)
        if (!Files.exists(dir)) return emptyList()
        return Files.list(dir).use { paths ->
            paths
                .filter { SEGMENT_FILE.matches(it.fileName.toString()) }
                .sorted(Comparator.comparingInt<Path> { segmentIndex(it) })
                .toList()
        }
    }

    fun segmentIndex(path: Path): Int =
        SEGMENT_FILE
            .matchEntire(path.fileName.toString())
            ?.groupValues
            ?.get(1)
            ?.toInt()
            ?: error("invalid segment file: $path")

    companion object {
        val UUID_SESSION_DIR = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
        val SEGMENT_FILE = Regex("segment-(\\d{6})\\.log")
    }
}
