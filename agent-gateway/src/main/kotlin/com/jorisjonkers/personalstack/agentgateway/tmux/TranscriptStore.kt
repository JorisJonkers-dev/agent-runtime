package com.jorisjonkers.personalstack.agentgateway.tmux

import com.jorisjonkers.personalstack.agentgateway.config.GatewayProperties
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.time.Clock
import java.time.Instant
import java.util.Comparator
import java.util.Properties
import java.util.UUID
import kotlin.io.path.Path
import kotlin.streams.toList

@Component
// Transcript metadata, leases, and segment paths share one lock namespace.
@Suppress("LargeClass", "TooManyFunctions")
class TranscriptStore
    @Autowired
    constructor(
        private val props: GatewayProperties,
    ) {
        private var clock: Clock = Clock.systemUTC()

        internal constructor(
            props: GatewayProperties,
            clock: Clock,
        ) : this(props) {
            this.clock = clock
        }

        fun validateStableSessionId(value: String): String = UUID.fromString(value).toString()

        fun root(): Path {
            val workspace = Path(props.workspaceRoot).toAbsolutePath().normalize()
            val root = workspace.resolve(props.transcripts.dirName).normalize()
            require(root.startsWith(workspace)) { "transcript directory must stay inside the workspace" }
            Files.createDirectories(root)
            return root
        }

        fun open(
            stableSessionId: String,
            epoch: Long,
        ): TranscriptMetadata {
            val id = validateStableSessionId(stableSessionId)
            return synchronized(lockFor(id)) {
                require(epoch > 0) { "epoch must be positive" }
                Files.createDirectories(segmentsDir(id))
                val metadata = recoverMetadata(id).copy(epoch = epoch, sealed = false)
                writeMetadata(id, metadata)
                metadata
            }
        }

        // Lease validation and atomic persistence are kept together under one lock.
        @Suppress("LongMethod")
        fun acquireLease(
            stableSessionId: String,
            owner: String,
            epoch: Long,
        ): TranscriptLease {
            val id = validateStableSessionId(stableSessionId)
            return synchronized(lockFor(id)) {
                require(epoch > 0) { "epoch must be positive" }
                Files.createDirectories(sessionDir(id))
                val leaseFile = leaseFile(id)
                val now = clock.millis()
                val existing = readLease(leaseFile)
                if (existing != null && existing.expiresAtMillis > now && existing.owner != owner) {
                    error("transcript $id is leased by ${existing.owner}")
                }
                val lease =
                    TranscriptLease(
                        stableSessionId = id,
                        owner = owner,
                        token = UUID.randomUUID().toString(),
                        epoch = epoch,
                        expiresAtMillis = now + props.transcripts.leaseTtlSeconds * MILLIS_PER_SECOND,
                    )
                writePropertiesAtomic(
                    leaseFile,
                    Properties().apply {
                        this["stableSessionId"] = lease.stableSessionId
                        this["owner"] = lease.owner
                        this["token"] = lease.token
                        this["epoch"] = lease.epoch.toString()
                        this["expiresAtMillis"] = lease.expiresAtMillis.toString()
                    },
                )
                lease
            }
        }

        fun releaseLease(lease: TranscriptLease) {
            val id = validateStableSessionId(lease.stableSessionId)
            synchronized(lockFor(id)) {
                val existing = readLease(leaseFile(id)) ?: return
                if (existing.token == lease.token) Files.deleteIfExists(leaseFile(id))
            }
        }

        fun renewLease(lease: TranscriptLease): TranscriptLease? {
            val id = validateStableSessionId(lease.stableSessionId)
            return synchronized(lockFor(id)) {
                val existing = readLease(leaseFile(id)) ?: return null
                if (existing.token != lease.token) return null
                val renewed =
                    existing.copy(
                        expiresAtMillis = clock.millis() + props.transcripts.leaseTtlSeconds * MILLIS_PER_SECOND,
                    )
                writePropertiesAtomic(
                    leaseFile(id),
                    Properties().apply {
                        this["stableSessionId"] = renewed.stableSessionId
                        this["owner"] = renewed.owner
                        this["token"] = renewed.token
                        this["epoch"] = renewed.epoch.toString()
                        this["expiresAtMillis"] = renewed.expiresAtMillis.toString()
                    },
                )
                renewed
            }
        }

        fun activeSegmentPath(stableSessionId: String): Path {
            val id = validateStableSessionId(stableSessionId)
            return synchronized(lockFor(id)) {
                val metadata = recoverMetadata(id)
                segmentPath(id, metadata.activeSegment).also {
                    Files.createDirectories(it.parent)
                    if (!Files.exists(it)) Files.createFile(it)
                }
            }
        }

        fun appendContinuationDelimiter(
            stableSessionId: String,
            epoch: Long,
            continuation: AgentContinuation?,
        ): TranscriptMetadata {
            val id = validateStableSessionId(stableSessionId)
            return synchronized(lockFor(id)) {
                var metadata = recoverMetadata(id)
                if (epoch in metadata.delimiterEpochs) return metadata

                val marker =
                    buildString {
                        append("\r\n")
                        append("[agent-gateway continuation epoch=")
                        append(epoch)
                        append(" agent restarted (updated setup)")
                        continuation?.previousEpoch?.let { append(" previousEpoch=").append(it) }
                        continuation?.reason?.takeIf { it.isNotBlank() }?.let {
                            append(" reason=").append(it.replace(Regex("\\s+"), " ").take(MAX_REASON_CHARS))
                        }
                        append("]\r\n")
                    }.toByteArray(Charsets.UTF_8)
                Files.write(activeSegmentPath(id), marker, StandardOpenOption.APPEND)
                metadata =
                    recoverMetadata(id).copy(
                        delimiterEpochs = metadata.delimiterEpochs + epoch,
                        sealed = false,
                    )
                writeMetadata(id, metadata)
                metadata
            }
        }

        fun recoverMetadata(stableSessionId: String): TranscriptMetadata {
            val id = validateStableSessionId(stableSessionId)
            return synchronized(lockFor(id)) {
                Files.createDirectories(segmentsDir(id))
                val existing = readMetadata(id)
                val segments = segmentFiles(id)
                if (segments.isEmpty()) {
                    val metadata =
                        (existing ?: TranscriptMetadata(stableSessionId = id)).copy(
                            logicalEnd = existing?.logicalStart ?: 0L,
                            activeSegment = 0,
                        )
                    Files.createFile(segmentPath(id, 0))
                    writeMetadata(id, metadata)
                    return metadata
                }
                val start = existing?.logicalStart ?: 0L
                val end = start + segments.sumOf { Files.size(it) }
                val active = segmentIndex(segments.last())
                val recovered =
                    (existing ?: TranscriptMetadata(stableSessionId = id)).copy(
                        stableSessionId = id,
                        logicalEnd = end,
                        activeSegment = active,
                    )
                writeMetadata(id, recovered)
                recovered
            }
        }

        fun rotateIfNeeded(stableSessionId: String): TranscriptMetadata {
            val id = validateStableSessionId(stableSessionId)
            return synchronized(lockFor(id)) {
                var metadata = recoverMetadata(id)
                val active = segmentPath(id, metadata.activeSegment)
                if (Files.exists(active) && Files.size(active) >= props.transcripts.segmentBytes) {
                    metadata = metadata.copy(activeSegment = metadata.activeSegment + 1)
                    val next = segmentPath(id, metadata.activeSegment)
                    if (!Files.exists(next)) Files.createFile(next)
                    writeMetadata(id, metadata)
                }
                metadata
            }
        }

        fun trimIfNeeded(stableSessionId: String): TranscriptMetadata {
            val id = validateStableSessionId(stableSessionId)
            return synchronized(lockFor(id)) {
                var metadata = recoverMetadata(id)
                val cap = props.transcripts.capBytes
                if (metadata.byteCount <= cap) return metadata

                val segments = segmentFiles(id).toMutableList()
                while (segments.size > 1 && metadata.byteCount > cap) {
                    val victim = segments.removeAt(0)
                    val bytes = Files.size(victim)
                    Files.deleteIfExists(victim)
                    metadata = metadata.copy(logicalStart = metadata.logicalStart + bytes)
                }
                metadata =
                    metadata.copy(
                        logicalEnd = metadata.logicalStart + segments.sumOf { Files.size(it) },
                        activeSegment = segmentIndex(segments.last()),
                    )
                writeMetadata(id, metadata)
                metadata
            }
        }

        fun seal(stableSessionId: String): TranscriptMetadata {
            val id = validateStableSessionId(stableSessionId)
            return synchronized(lockFor(id)) {
                val metadata = recoverMetadata(id).copy(sealed = true, updatedAt = Instant.now(clock))
                writeMetadata(id, metadata)
                metadata
            }
        }

        // Early exits map directly to cleanup safety gates.
        @Suppress("ReturnCount")
        fun cleanup(stableSessionId: String): Boolean {
            val id = validateStableSessionId(stableSessionId)
            return synchronized(lockFor(id)) {
                val dir = sessionDir(id)
                if (!Files.exists(dir)) return false
                val metadata = readMetadata(id)
                val lease = readLease(leaseFile(id))
                if (lease != null && lease.expiresAtMillis > clock.millis()) return false
                if (metadata != null && !metadata.sealed) return false
                val cutoff = Instant.now(clock).minusSeconds(props.transcripts.retentionSeconds)
                if (metadata != null && metadata.updatedAt.isAfter(cutoff)) return false
                Files.walk(dir).use { paths ->
                    paths.sorted(Comparator.reverseOrder<Path>()).forEach { Files.deleteIfExists(it) }
                }
                true
            }
        }

        // The segment scan advances or stops at precise byte boundaries.
        @Suppress("LoopWithTooManyJumpStatements")
        fun readRaw(
            stableSessionId: String,
            fromOffset: Long,
            maxBytes: Int = DEFAULT_READ_BYTES,
        ): TranscriptRawRead {
            val id = validateStableSessionId(stableSessionId)
            return synchronized(lockFor(id)) {
                val metadata = recoverMetadata(id)
                val start = fromOffset.coerceAtLeast(metadata.logicalStart)
                if (start >= metadata.logicalEnd) {
                    return TranscriptRawRead(startOffset = start, bytes = ByteArray(0), metadata = metadata)
                }
                val out = ByteArrayBuilder(maxBytes)
                var segmentStart = metadata.logicalStart
                for (segment in segmentFiles(id)) {
                    val segmentSize = Files.size(segment)
                    val segmentEnd = segmentStart + segmentSize
                    if (segmentEnd <= start) {
                        segmentStart = segmentEnd
                        continue
                    }
                    val offsetInSegment = (start + out.size - segmentStart).coerceAtLeast(0)
                    if (offsetInSegment < segmentSize) {
                        Files.newInputStream(segment).use { input ->
                            input.skipFully(offsetInSegment)
                            out.readFrom(input, maxBytes - out.size)
                        }
                    }
                    if (out.size >= maxBytes) break
                    segmentStart = segmentEnd
                }
                TranscriptRawRead(startOffset = start, bytes = out.toByteArray(), metadata = metadata)
            }
        }

        private fun sessionDir(stableSessionId: String): Path = root().resolve(stableSessionId)

        private fun segmentsDir(stableSessionId: String): Path = sessionDir(stableSessionId).resolve("segments")

        private fun metadataFile(stableSessionId: String): Path =
            sessionDir(stableSessionId).resolve("metadata.properties")

        private fun leaseFile(stableSessionId: String): Path = sessionDir(stableSessionId).resolve("lease.properties")

        private fun segmentPath(
            stableSessionId: String,
            index: Int,
        ): Path = segmentsDir(stableSessionId).resolve("segment-%06d.log".format(index))

        private fun segmentFiles(stableSessionId: String): List<Path> {
            val dir = segmentsDir(stableSessionId)
            if (!Files.exists(dir)) return emptyList()
            return Files.list(dir).use { paths ->
                paths
                    .filter { SEGMENT_FILE.matches(it.fileName.toString()) }
                    .sorted(Comparator.comparingInt<Path> { segmentIndex(it) })
                    .toList()
            }
        }

        private fun segmentIndex(path: Path): Int =
            SEGMENT_FILE
                .matchEntire(path.fileName.toString())
                ?.groupValues
                ?.get(1)
                ?.toInt()
                ?: error("invalid segment file: $path")

        private fun readMetadata(stableSessionId: String): TranscriptMetadata? {
            val file = metadataFile(stableSessionId)
            if (!Files.exists(file)) return null
            val p = Properties()
            Files.newInputStream(file).use(p::load)
            return TranscriptMetadata(
                stableSessionId = p.getProperty("stableSessionId", stableSessionId),
                epoch = p.getProperty("epoch", "1").toLong(),
                logicalStart = p.getProperty("logicalStart", "0").toLong(),
                logicalEnd = p.getProperty("logicalEnd", "0").toLong(),
                activeSegment = p.getProperty("activeSegment", "0").toInt(),
                sealed = p.getProperty("sealed", "false").toBoolean(),
                delimiterEpochs =
                    p
                        .getProperty("delimiterEpochs", "")
                        .split(',')
                        .mapNotNull { it.trim().takeIf(String::isNotBlank)?.toLong() }
                        .toSet(),
                updatedAt = Instant.ofEpochMilli(p.getProperty("updatedAtMillis", "0").toLong()),
            )
        }

        private fun writeMetadata(
            stableSessionId: String,
            metadata: TranscriptMetadata,
        ) {
            val now = Instant.now(clock)
            val p =
                Properties().apply {
                    this["stableSessionId"] = metadata.stableSessionId
                    this["epoch"] = metadata.epoch.toString()
                    this["logicalStart"] = metadata.logicalStart.toString()
                    this["logicalEnd"] = metadata.logicalEnd.toString()
                    this["activeSegment"] = metadata.activeSegment.toString()
                    this["sealed"] = metadata.sealed.toString()
                    this["delimiterEpochs"] = metadata.delimiterEpochs.sorted().joinToString(",")
                    this["updatedAtMillis"] = now.toEpochMilli().toString()
                }
            writePropertiesAtomic(metadataFile(stableSessionId), p)
        }

        private fun readLease(file: Path): TranscriptLease? {
            if (!Files.exists(file)) return null
            val p = Properties()
            Files.newInputStream(file).use(p::load)
            return TranscriptLease(
                stableSessionId = p.getProperty("stableSessionId"),
                owner = p.getProperty("owner"),
                token = p.getProperty("token"),
                epoch = p.getProperty("epoch", "1").toLong(),
                expiresAtMillis = p.getProperty("expiresAtMillis", "0").toLong(),
            )
        }

        private fun writePropertiesAtomic(
            file: Path,
            properties: Properties,
        ) {
            Files.createDirectories(file.parent)
            val tmp = file.resolveSibling("${file.fileName}.tmp-${UUID.randomUUID()}")
            Files.newOutputStream(tmp, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).use { out ->
                properties.store(out, null)
            }
            runCatching {
                Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            }.getOrElse {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING)
            }
        }

        private fun lockFor(stableSessionId: String): Any = locks.computeIfAbsent(stableSessionId) { Any() }

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
            private const val MILLIS_PER_SECOND = 1_000L
            private const val MAX_REASON_CHARS = 160
            private val SEGMENT_FILE = Regex("segment-(\\d{6})\\.log")
            private val locks = java.util.concurrent.ConcurrentHashMap<String, Any>()
        }
    }

data class TranscriptMetadata(
    val stableSessionId: String,
    val epoch: Long = 1,
    val logicalStart: Long = 0,
    val logicalEnd: Long = 0,
    val activeSegment: Int = 0,
    val sealed: Boolean = false,
    val delimiterEpochs: Set<Long> = emptySet(),
    val updatedAt: Instant = Instant.EPOCH,
) {
    val byteCount: Long get() = logicalEnd - logicalStart
}

data class TranscriptLease(
    val stableSessionId: String,
    val owner: String,
    val token: String,
    val epoch: Long,
    val expiresAtMillis: Long,
)

data class TranscriptRawRead(
    val startOffset: Long,
    val bytes: ByteArray,
    val metadata: TranscriptMetadata,
)
