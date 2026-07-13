package com.jorisjonkers.personalstack.agentgateway.tmux

import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties
import java.util.UUID

internal class TranscriptLeaseStore(
    private val context: TranscriptStoreContext,
) {
    fun acquire(
        stableSessionId: String,
        owner: String,
        epoch: Long,
    ): TranscriptLease {
        val id = context.validateStableSessionId(stableSessionId)
        return synchronized(context.lockFor(id)) {
            require(epoch > 0) { "epoch must be positive" }
            Files.createDirectories(context.paths.sessionDir(id))
            val leaseFile = context.paths.leaseFile(id)
            val now = context.clock.millis()
            val existing = read(leaseFile)
            if (existing != null && existing.expiresAtMillis > now && existing.owner != owner) {
                error("transcript $id is leased by ${existing.owner}")
            }
            write(
                TranscriptLease(
                    stableSessionId = id,
                    owner = owner,
                    token = UUID.randomUUID().toString(),
                    epoch = epoch,
                    expiresAtMillis = now + context.props.transcripts.leaseTtlSeconds * MILLIS_PER_SECOND,
                ),
            )
        }
    }

    fun release(lease: TranscriptLease) {
        val id = context.validateStableSessionId(lease.stableSessionId)
        synchronized(context.lockFor(id)) {
            val existing = read(context.paths.leaseFile(id)) ?: return
            if (existing.token == lease.token) Files.deleteIfExists(context.paths.leaseFile(id))
        }
    }

    fun renew(lease: TranscriptLease): TranscriptLease? {
        val id = context.validateStableSessionId(lease.stableSessionId)
        return synchronized(context.lockFor(id)) {
            read(context.paths.leaseFile(id))
                ?.takeIf { it.token == lease.token }
                ?.let { current ->
                    write(
                        current.copy(
                            expiresAtMillis =
                                context.clock.millis() +
                                    context.props.transcripts.leaseTtlSeconds * MILLIS_PER_SECOND,
                        ),
                    )
                }
        }
    }

    fun read(file: Path): TranscriptLease? {
        if (!Files.exists(file)) return null
        val properties = Properties()
        Files.newInputStream(file).use(properties::load)
        return TranscriptLease(
            stableSessionId = properties.getProperty("stableSessionId"),
            owner = properties.getProperty("owner"),
            token = properties.getProperty("token"),
            epoch = properties.getProperty("epoch", "1").toLong(),
            expiresAtMillis = properties.getProperty("expiresAtMillis", "0").toLong(),
        )
    }

    private fun write(lease: TranscriptLease): TranscriptLease {
        context.writePropertiesAtomic(
            context.paths.leaseFile(lease.stableSessionId),
            Properties().apply {
                this["stableSessionId"] = lease.stableSessionId
                this["owner"] = lease.owner
                this["token"] = lease.token
                this["epoch"] = lease.epoch.toString()
                this["expiresAtMillis"] = lease.expiresAtMillis.toString()
            },
        )
        return lease
    }

    companion object {
        private const val MILLIS_PER_SECOND = 1_000L
    }
}
