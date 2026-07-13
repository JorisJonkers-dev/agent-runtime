package com.jorisjonkers.personalstack.agentgateway.tmux

import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

internal class TranscriptSegmentStore(
    private val context: TranscriptStoreContext,
    private val metadataStore: TranscriptMetadataStore,
) {
    fun activeSegmentPath(stableSessionId: String): Path {
        val id = context.validateStableSessionId(stableSessionId)
        return synchronized(context.lockFor(id)) {
            val metadata = metadataStore.recover(id)
            context.paths.segmentPath(id, metadata.activeSegment).also {
                Files.createDirectories(it.parent)
                if (!Files.exists(it)) Files.createFile(it)
            }
        }
    }

    fun rotateIfNeeded(stableSessionId: String): TranscriptMetadata {
        val id = context.validateStableSessionId(stableSessionId)
        return synchronized(context.lockFor(id)) {
            var metadata = metadataStore.recover(id)
            val active = context.paths.segmentPath(id, metadata.activeSegment)
            if (Files.exists(active) && Files.size(active) >= context.props.transcripts.segmentBytes) {
                metadata = metadata.copy(activeSegment = metadata.activeSegment + 1)
                val next = context.paths.segmentPath(id, metadata.activeSegment)
                if (!Files.exists(next)) Files.createFile(next)
                metadataStore.write(id, metadata)
            }
            metadata
        }
    }

    fun trimIfNeeded(stableSessionId: String): TranscriptMetadata {
        val id = context.validateStableSessionId(stableSessionId)
        return synchronized(context.lockFor(id)) {
            var metadata = metadataStore.recover(id)
            val cap = context.props.transcripts.capBytes
            if (metadata.byteCount <= cap) return@synchronized metadata

            val segments = context.paths.segmentFiles(id).toMutableList()
            while (segments.size > 1 && metadata.byteCount > cap) {
                val victim = segments.removeAt(0)
                val bytes = Files.size(victim)
                Files.deleteIfExists(victim)
                metadata = metadata.copy(logicalStart = metadata.logicalStart + bytes)
            }
            metadata =
                metadata.copy(
                    logicalEnd = metadata.logicalStart + segments.sumOf { Files.size(it) },
                    activeSegment = context.paths.segmentIndex(segments.last()),
                )
            metadataStore.write(id, metadata)
            metadata
        }
    }

    fun readRaw(
        stableSessionId: String,
        fromOffset: Long,
        maxBytes: Int = DEFAULT_READ_BYTES,
    ): TranscriptRawRead {
        val id = context.validateStableSessionId(stableSessionId)
        return synchronized(context.lockFor(id)) {
            val metadata = metadataStore.recover(id)
            val start = fromOffset.coerceAtLeast(metadata.logicalStart)
            val bytes =
                if (start >= metadata.logicalEnd) {
                    ByteArray(0)
                } else {
                    readSegments(id, start, maxBytes, metadata.logicalStart)
                }
            TranscriptRawRead(startOffset = start, bytes = bytes, metadata = metadata)
        }
    }

    fun writeContinuationMarker(
        stableSessionId: String,
        marker: ByteArray,
    ) {
        Files.write(activeSegmentPath(stableSessionId), marker, StandardOpenOption.APPEND)
    }

    private fun readSegments(
        stableSessionId: String,
        start: Long,
        maxBytes: Int,
        logicalStart: Long,
    ): ByteArray {
        val out = ByteArrayBuilder(maxBytes)
        var segmentStart = logicalStart
        val segments = context.paths.segmentFiles(stableSessionId)
        var index = 0
        while (index < segments.size && out.size < maxBytes) {
            val segment = segments[index]
            val segmentSize = Files.size(segment)
            val segmentEnd = segmentStart + segmentSize
            if (segmentEnd > start) {
                readFromSegment(segment, start + out.size - segmentStart, segmentSize, out, maxBytes)
            }
            segmentStart = segmentEnd
            index++
        }
        return out.toByteArray()
    }

    private fun readFromSegment(
        segment: Path,
        offset: Long,
        segmentSize: Long,
        out: ByteArrayBuilder,
        maxBytes: Int,
    ) {
        val offsetInSegment = offset.coerceAtLeast(0)
        if (offsetInSegment >= segmentSize) return
        Files.newInputStream(segment).use { input ->
            input.skipFully(offsetInSegment)
            out.readFrom(input, maxBytes - out.size)
        }
    }

    private fun InputStream.skipFully(bytes: Long) {
        var remaining = bytes
        while (remaining > 0) {
            val skipped = skip(remaining)
            if (skipped <= 0) break
            remaining -= skipped
        }
    }

    private class ByteArrayBuilder(
        capacity: Int,
    ) {
        private val bytes = ByteArray(capacity)
        var size: Int = 0
            private set

        fun readFrom(
            input: InputStream,
            limit: Int,
        ) {
            var remaining = limit
            while (remaining > 0) {
                val read = input.read(bytes, size, remaining)
                if (read <= 0) return
                size += read
                remaining -= read
            }
        }

        fun toByteArray(): ByteArray = bytes.copyOf(size)
    }

    companion object {
        private const val DEFAULT_READ_BYTES = 64 * 1024
    }
}
