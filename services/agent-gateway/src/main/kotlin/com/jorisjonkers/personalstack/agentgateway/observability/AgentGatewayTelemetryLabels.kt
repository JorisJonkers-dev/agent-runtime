@file:Suppress("CyclomaticComplexMethod")

package com.jorisjonkers.personalstack.agentgateway.observability

interface GatewayTelemetryLabel {
    val label: String
}

enum class GatewayAgentKindLabel(
    override val label: String,
) : GatewayTelemetryLabel {
    CLAUDE("claude"),
    CODEX("codex"),
    SHELL("shell"),
    OTHER("other"),
    ;

    companion object {
        fun fromRaw(kind: String?): GatewayAgentKindLabel =
            when (normalize(kind)) {
                "claude" -> CLAUDE
                "codex" -> CODEX
                "shell", "bash" -> SHELL
                else -> OTHER
            }
    }
}

enum class GatewayStatusLabel(
    override val label: String,
) : GatewayTelemetryLabel {
    STARTING("starting"),
    RUNNING("running"),
    COMPLETED("completed"),
    FAILED("failed"),
    CANCELLED("cancelled"),
    UNKNOWN("unknown"),
    OTHER("other"),
    ;

    companion object {
        fun fromRaw(status: String?): GatewayStatusLabel =
            when (normalize(status)) {
                null -> UNKNOWN
                "starting", "pending" -> STARTING
                "running", "ready" -> RUNNING
                "completed", "complete", "succeeded", "success" -> COMPLETED
                "failed", "failure", "error" -> FAILED
                "cancelled", "canceled" -> CANCELLED
                else -> OTHER
            }
    }
}

enum class GatewayOperationLabel(
    override val label: String,
) : GatewayTelemetryLabel {
    SPAWN("spawn"),
    ATTACH("attach"),
    INPUT("input"),
    RESIZE("resize"),
    REPLAY("replay"),
    STOP("stop"),
    HEADLESS_JOB("headless_job"),
    UNKNOWN("unknown"),
    OTHER("other"),
    ;

    companion object {
        fun fromRaw(operation: String?): GatewayOperationLabel =
            when (normalize(operation)) {
                null -> UNKNOWN
                "spawn", "start", "create" -> SPAWN
                "attach", "connect" -> ATTACH
                "input", "send", "send_input" -> INPUT
                "resize" -> RESIZE
                "replay", "tail", "transcript_replay" -> REPLAY
                "stop", "kill" -> STOP
                "headless", "headless_job", "job" -> HEADLESS_JOB
                else -> OTHER
            }
    }
}

enum class GatewayModeLabel(
    override val label: String,
) : GatewayTelemetryLabel {
    LIVE("live"),
    RESUME("resume"),
    SNAPSHOT("snapshot"),
    INTERACTIVE("interactive"),
    HEADLESS("headless"),
    DURABLE("durable"),
    EPHEMERAL("ephemeral"),
    UNKNOWN("unknown"),
    OTHER("other"),
    ;

    companion object {
        fun fromRaw(mode: String?): GatewayModeLabel =
            when (normalize(mode)) {
                null -> UNKNOWN
                "live" -> LIVE
                "resume" -> RESUME
                "snapshot" -> SNAPSHOT
                "interactive", "attach", "terminal", "websocket" -> INTERACTIVE
                "headless", "job", "batch" -> HEADLESS
                "durable", "persisted" -> DURABLE
                "ephemeral", "temporary" -> EPHEMERAL
                else -> OTHER
            }
    }
}

enum class GatewayOutcomeLabel(
    override val label: String,
) : GatewayTelemetryLabel {
    SUCCESS("success"),
    FAILURE("failure"),
    CANCELLED("cancelled"),
    SKIPPED("skipped"),
    UNKNOWN("unknown"),
    ;

    companion object {
        fun fromRaw(outcome: String?): GatewayOutcomeLabel =
            when (normalize(outcome)) {
                null -> UNKNOWN
                "success", "succeeded", "ok", "completed" -> SUCCESS
                "failure", "failed", "error" -> FAILURE
                "cancelled", "canceled" -> CANCELLED
                "skipped", "noop", "no_op" -> SKIPPED
                else -> UNKNOWN
            }
    }
}

enum class GatewayStorageObjectLabel(
    override val label: String,
) : GatewayTelemetryLabel {
    TRANSCRIPT("transcript"),
    WORKSPACE_PVC("workspace_pvc"),
    STAGED_INPUT("staged_input"),
    TMUX_PIPE("tmux_pipe"),
    OTHER("other"),
    ;

    companion object {
        fun fromRaw(storageObject: String?): GatewayStorageObjectLabel =
            when (normalize(storageObject)) {
                "transcript", "transcripts" -> TRANSCRIPT
                "workspace_pvc", "pvc", "volume" -> WORKSPACE_PVC
                "staged_input", "input" -> STAGED_INPUT
                "tmux_pipe", "pipe" -> TMUX_PIPE
                else -> OTHER
            }
    }
}

enum class GatewayFailureReasonLabel(
    override val label: String,
) : GatewayTelemetryLabel {
    NONE("none"),
    NOT_FOUND("not_found"),
    INVALID_REQUEST("invalid_request"),
    PROCESS_EXITED("process_exited"),
    TMUX_UNAVAILABLE("tmux_unavailable"),
    TIMEOUT("timeout"),
    IO_ERROR("io_error"),
    PERMISSION_DENIED("permission_denied"),
    CAPACITY("capacity"),
    CANCELLED("cancelled"),
    UNKNOWN("unknown"),
    OTHER("other"),
    ;

    companion object {
        fun fromRaw(reason: String?): GatewayFailureReasonLabel {
            val normalized = normalize(reason) ?: return UNKNOWN
            return when {
                normalized in setOf("none", "success", "ok") -> NONE
                normalized in setOf("not_found", "missing", "gone") -> NOT_FOUND
                normalized in setOf("invalid", "invalid_request", "bad_request") -> INVALID_REQUEST
                normalized in setOf("process_exited", "exit", "exited") -> PROCESS_EXITED
                normalized in setOf("tmux", "tmux_unavailable") -> TMUX_UNAVAILABLE
                "timeout" in normalized || "timed_out" in normalized -> TIMEOUT
                normalized in setOf("io", "io_error", "filesystem") -> IO_ERROR
                normalized in setOf("permission", "permission_denied", "forbidden") -> PERMISSION_DENIED
                normalized in setOf("capacity", "quota", "storage_full") -> CAPACITY
                normalized in setOf("cancelled", "canceled") -> CANCELLED
                normalized == "unknown" -> UNKNOWN
                else -> OTHER
            }
        }
    }
}

private fun normalize(value: String?): String? {
    val trimmed = value?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
    return trimmed.replace('-', '_').replace('.', '_').replace(' ', '_')
}
