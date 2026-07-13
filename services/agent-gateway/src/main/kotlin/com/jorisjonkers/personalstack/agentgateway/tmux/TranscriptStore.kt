package com.jorisjonkers.personalstack.agentgateway.tmux

import com.jorisjonkers.personalstack.agentgateway.config.GatewayProperties
import com.jorisjonkers.personalstack.agentgateway.observability.AgentGatewayTelemetry
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component
import java.nio.file.Path
import java.time.Clock
import java.time.Instant

@Component
class TranscriptStore private constructor(
    private val context: TranscriptStoreContext,
) {
    @Autowired
    constructor(
        props: GatewayProperties,
        telemetry: AgentGatewayTelemetry = AgentGatewayTelemetry.NOOP,
    ) : this(TranscriptStoreContext(props, Clock.systemUTC(), telemetry))

    internal constructor(
        props: GatewayProperties,
        clock: Clock,
        telemetry: AgentGatewayTelemetry = AgentGatewayTelemetry.NOOP,
    ) : this(TranscriptStoreContext(props, clock, telemetry))

    private val leaseStore = TranscriptLeaseStore(context)
    private val metadataStore = TranscriptMetadataStore(context)
    private val segmentStore = TranscriptSegmentStore(context, metadataStore)
    private val continuationStore = TranscriptContinuationStore(context, metadataStore, segmentStore)
    private val storageStatsStore = TranscriptStorageStatsStore(context)

    fun validateStableSessionId(value: String): String = context.validateStableSessionId(value)

    fun root(): Path = context.root()

    fun open(
        stableSessionId: String,
        epoch: Long,
    ): TranscriptMetadata =
        metadataStore.open(stableSessionId, epoch).also { refreshStorageStats() }

    fun acquireLease(
        stableSessionId: String,
        owner: String,
        epoch: Long,
    ): TranscriptLease = leaseStore.acquire(stableSessionId, owner, epoch)

    fun releaseLease(lease: TranscriptLease) {
        leaseStore.release(lease)
    }

    fun renewLease(lease: TranscriptLease): TranscriptLease? = leaseStore.renew(lease)

    fun activeSegmentPath(stableSessionId: String): Path = segmentStore.activeSegmentPath(stableSessionId)

    fun appendContinuationDelimiter(
        stableSessionId: String,
        epoch: Long,
        continuation: AgentContinuation?,
    ): TranscriptMetadata = continuationStore.appendDelimiter(stableSessionId, epoch, continuation)

    fun recoverMetadata(stableSessionId: String): TranscriptMetadata = metadataStore.recover(stableSessionId)

    fun rotateIfNeeded(stableSessionId: String): TranscriptMetadata =
        segmentStore.rotateIfNeeded(stableSessionId).also { refreshStorageStats() }

    fun trimIfNeeded(stableSessionId: String): TranscriptMetadata =
        segmentStore.trimIfNeeded(stableSessionId).also { refreshStorageStats() }

    fun seal(stableSessionId: String): TranscriptMetadata =
        metadataStore.seal(stableSessionId).also { refreshStorageStats() }

    fun cleanup(stableSessionId: String): Boolean =
        metadataStore.cleanup(stableSessionId, leaseStore).also { refreshStorageStats() }

    fun storageStats(): TranscriptStorageStats = storageStatsStore.current()

    fun refreshStorageStats(): TranscriptStorageStats = storageStatsStore.refresh()

    fun readRaw(
        stableSessionId: String,
        fromOffset: Long,
        maxBytes: Int = DEFAULT_READ_BYTES,
    ): TranscriptRawRead = segmentStore.readRaw(stableSessionId, fromOffset, maxBytes)

    companion object {
        private const val DEFAULT_READ_BYTES = 64 * 1024
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

data class TranscriptStorageStats(
    val usedBytes: Long,
    val capBytes: Long,
    val refreshedAt: Instant,
)
