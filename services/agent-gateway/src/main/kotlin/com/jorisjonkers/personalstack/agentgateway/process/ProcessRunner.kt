package com.jorisjonkers.personalstack.agentgateway.process

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Thin wrapper around ProcessBuilder. Centralises stdout/stderr capture
 * and timeout handling so tmux / git / claude / codex shell-outs share
 * exactly one error path.
 *
 * Why a tiny custom runner rather than a library: the call shape is
 * uniform (single argv list, optional working dir, optional env, capture
 * combined output, fail fast on non-zero unless told otherwise), and a
 * library would still get wrapped to enforce that shape. Twenty lines
 * here saves a dependency.
 */
@Component
open class ProcessRunner {
    private val log = LoggerFactory.getLogger(ProcessRunner::class.java)

    data class Result(
        val exitCode: Int,
        val stdout: String,
        val stderr: String,
    ) {
        val combined: String get() = if (stderr.isEmpty()) stdout else stdout + "\n" + stderr
    }

    open fun run(
        argv: List<String>,
        cwd: File? = null,
        env: Map<String, String> = emptyMap(),
        timeoutSeconds: Long = 30,
        checked: Boolean = true,
    ): Result {
        log.debug("exec {} (cwd={})", argv, cwd)
        val pb =
            ProcessBuilder(argv).apply {
                if (cwd != null) directory(cwd)
                env.forEach { (k, v) -> environment()[k] = v }
                redirectErrorStream(false)
            }
        val process = pb.start()
        val finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
        if (!finished) {
            process.destroyForcibly()
            throw ProcessTimeoutException("timed out after ${timeoutSeconds}s: $argv")
        }
        val stdout = process.inputStream.bufferedReader().use { it.readText() }
        val stderr = process.errorStream.bufferedReader().use { it.readText() }
        val result = Result(process.exitValue(), stdout, stderr)
        if (checked && result.exitCode != 0) {
            throw ProcessFailedException(argv, result)
        }
        return result
    }
}

class ProcessTimeoutException(
    msg: String,
) : RuntimeException(msg)

class ProcessFailedException(
    val argv: List<String>,
    val result: ProcessRunner.Result,
) : RuntimeException("$argv exited ${result.exitCode}: ${result.stderr.take(STDERR_PREVIEW_CHARS)}") {
    companion object {
        private const val STDERR_PREVIEW_CHARS = 500
    }
}
