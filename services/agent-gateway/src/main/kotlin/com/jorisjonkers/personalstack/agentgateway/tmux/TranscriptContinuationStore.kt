package com.jorisjonkers.personalstack.agentgateway.tmux

internal class TranscriptContinuationStore(
    private val context: TranscriptStoreContext,
    private val metadataStore: TranscriptMetadataStore,
    private val segmentStore: TranscriptSegmentStore,
) {
    fun appendDelimiter(
        stableSessionId: String,
        epoch: Long,
        continuation: AgentContinuation?,
    ): TranscriptMetadata {
        val id = context.validateStableSessionId(stableSessionId)
        return synchronized(context.lockFor(id)) {
            val metadata = metadataStore.recover(id)
            if (epoch in metadata.delimiterEpochs) return@synchronized metadata

            segmentStore.writeContinuationMarker(id, marker(epoch, continuation))
            metadataStore
                .recover(id)
                .copy(delimiterEpochs = metadata.delimiterEpochs + epoch, sealed = false)
                .also { metadataStore.write(id, it) }
        }
    }

    private fun marker(
        epoch: Long,
        continuation: AgentContinuation?,
    ): ByteArray =
        buildString {
            append("\r\n")
            append("[agent-gateway continuation epoch=")
            append(epoch)
            append(' ')
            append(continuation.summary())
            continuation?.previousEpoch?.let { append(" previousEpoch=").append(it) }
            continuation?.reason?.takeIf { it.isNotBlank() }?.let {
                append(" reason=")
                append(redactSecrets(it.replace(Regex("\\s+"), " ")).take(MAX_REASON_CHARS))
            }
            append("]\r\n")
        }.toByteArray(Charsets.UTF_8)

    private fun AgentContinuation?.summary(): String {
        val reason = this?.reason?.trim()?.lowercase()
        val from = this?.fromSetupLabel.redactedLabel()
        val to = this?.toSetupLabel.redactedLabel()
        if (from != null || to != null) {
            return buildString {
                append("setup transition")
                from?.let { append(" fromSetup=").append(it) }
                to?.let { append(" toSetup=").append(it) }
            }
        }
        return when (reason) {
            "rebind" -> "agent rebound"
            else -> "agent restarted"
        }
    }

    private fun String?.redactedLabel(): String? =
        this
            ?.replace(Regex("\\s+"), " ")
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.let(::redactSecrets)
            ?.take(MAX_SETUP_LABEL_CHARS)
            ?.let { "\"${it.replace("\\", "\\\\").replace("\"", "\\\"")}\"" }

    private fun redactSecrets(value: String): String =
        SECRET_ASSIGNMENT.replace(SECRET_TOKEN.replace(value, "[redacted]")) {
            "${it.groupValues[1]}=[redacted]"
        }

    companion object {
        private const val MAX_REASON_CHARS = 160
        private const val MAX_SETUP_LABEL_CHARS = 160
        private val SECRET_TOKEN =
            Regex("""(?i)\b(?:sk-[A-Za-z0-9_-]{12,}|gh[pousr]_[A-Za-z0-9_]{12,}|github_pat_[A-Za-z0-9_]{12,})""")
        private val SECRET_ASSIGNMENT =
            Regex("""(?i)\b(password|passwd|secret|token|api[_-]?key|bearer|credential)=\S+""")
    }
}
