package com.jorisjonkers.personalstack.agentgateway.tmux

import java.nio.file.Files
import java.time.Instant
import java.util.Comparator
import java.util.Properties

internal class TranscriptMetadataStore(
    private val context: TranscriptStoreContext,
) {
    fun open(
        stableSessionId: String,
        epoch: Long,
    ): TranscriptMetadata {
        val id = context.validateStableSessionId(stableSessionId)
        return synchronized(context.lockFor(id)) {
            require(epoch > 0) { "epoch must be positive" }
            Files.createDirectories(context.paths.segmentsDir(id))
            recover(id).copy(epoch = epoch, sealed = false).also { write(id, it) }
        }
    }

    fun recover(stableSessionId: String): TranscriptMetadata {
        val id = context.validateStableSessionId(stableSessionId)
        return synchronized(context.lockFor(id)) {
            Files.createDirectories(context.paths.segmentsDir(id))
            val existing = read(id)
            val segments = context.paths.segmentFiles(id)
            if (segments.isEmpty()) {
                val metadata =
                    (existing ?: TranscriptMetadata(stableSessionId = id)).copy(
                        logicalEnd = existing?.logicalStart ?: 0L,
                        activeSegment = 0,
                    )
                Files.createFile(context.paths.segmentPath(id, 0))
                write(id, metadata)
                return@synchronized metadata
            }
            recoverFromSegments(id, existing, segments)
        }
    }

    fun seal(stableSessionId: String): TranscriptMetadata {
        val id = context.validateStableSessionId(stableSessionId)
        return synchronized(context.lockFor(id)) {
            recover(id)
                .copy(sealed = true, updatedAt = Instant.now(context.clock))
                .also { write(id, it) }
        }
    }

    fun cleanup(
        stableSessionId: String,
        leaseStore: TranscriptLeaseStore,
    ): Boolean {
        val id = context.validateStableSessionId(stableSessionId)
        return synchronized(context.lockFor(id)) {
            val dir = context.paths.sessionDir(id)
            if (!Files.exists(dir)) return@synchronized false
            val metadata = read(id)
            val lease = leaseStore.read(context.paths.leaseFile(id))
            if (lease != null && lease.expiresAtMillis > context.clock.millis()) return@synchronized false
            if (metadata != null && !metadata.sealed) return@synchronized false
            val cutoff = Instant.now(context.clock).minusSeconds(context.props.transcripts.retentionSeconds)
            if (metadata != null && metadata.updatedAt.isAfter(cutoff)) return@synchronized false
            Files.walk(dir).use { paths ->
                paths.sorted(Comparator.reverseOrder<java.nio.file.Path>()).forEach { Files.deleteIfExists(it) }
            }
            true
        }
    }

    fun read(stableSessionId: String): TranscriptMetadata? {
        val file = context.paths.metadataFile(stableSessionId)
        if (!Files.exists(file)) return null
        val properties = Properties()
        Files.newInputStream(file).use(properties::load)
        return TranscriptMetadata(
            stableSessionId = properties.getProperty("stableSessionId", stableSessionId),
            epoch = properties.getProperty("epoch", "1").toLong(),
            logicalStart = properties.getProperty("logicalStart", "0").toLong(),
            logicalEnd = properties.getProperty("logicalEnd", "0").toLong(),
            activeSegment = properties.getProperty("activeSegment", "0").toInt(),
            sealed = properties.getProperty("sealed", "false").toBoolean(),
            delimiterEpochs = delimiterEpochs(properties),
            updatedAt = Instant.ofEpochMilli(properties.getProperty("updatedAtMillis", "0").toLong()),
        )
    }

    fun write(
        stableSessionId: String,
        metadata: TranscriptMetadata,
    ) {
        val now = Instant.now(context.clock)
        context.writePropertiesAtomic(
            context.paths.metadataFile(stableSessionId),
            Properties().apply {
                this["stableSessionId"] = metadata.stableSessionId
                this["epoch"] = metadata.epoch.toString()
                this["logicalStart"] = metadata.logicalStart.toString()
                this["logicalEnd"] = metadata.logicalEnd.toString()
                this["activeSegment"] = metadata.activeSegment.toString()
                this["sealed"] = metadata.sealed.toString()
                this["delimiterEpochs"] = metadata.delimiterEpochs.sorted().joinToString(",")
                this["updatedAtMillis"] = now.toEpochMilli().toString()
            },
        )
    }

    private fun recoverFromSegments(
        stableSessionId: String,
        existing: TranscriptMetadata?,
        segments: List<java.nio.file.Path>,
    ): TranscriptMetadata {
        val start = existing?.logicalStart ?: 0L
        val end = start + segments.sumOf { Files.size(it) }
        val active = context.paths.segmentIndex(segments.last())
        val recovered =
            (existing ?: TranscriptMetadata(stableSessionId = stableSessionId)).copy(
                stableSessionId = stableSessionId,
                logicalEnd = end,
                activeSegment = active,
            )
        write(stableSessionId, recovered)
        return recovered
    }

    private fun delimiterEpochs(properties: Properties): Set<Long> =
        properties
            .getProperty("delimiterEpochs", "")
            .split(',')
            .mapNotNull { it.trim().takeIf(String::isNotBlank)?.toLong() }
            .toSet()
}
