package com.jorisjonkers.personalstack.agentgateway.web

import com.jorisjonkers.personalstack.agentgateway.process.ProcessFailedException
import com.jorisjonkers.personalstack.agentgateway.process.ProcessTimeoutException
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

@RestControllerAdvice
class ErrorAdvice {
    @ExceptionHandler(ProcessFailedException::class)
    fun onProcessFailed(e: ProcessFailedException): ResponseEntity<Map<String, Any>> =
        ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(
            mapOf(
                "error" to "process-failed",
                "argv" to e.argv,
                "exit" to e.result.exitCode,
                "stderr" to e.result.stderr.take(STDERR_PREVIEW_CHARS),
            ),
        )

    @ExceptionHandler(ProcessTimeoutException::class)
    fun onTimeout(e: ProcessTimeoutException): ResponseEntity<Map<String, String>> =
        ResponseEntity
            .status(HttpStatus.GATEWAY_TIMEOUT)
            .body(mapOf("error" to "timeout", "message" to e.message.orEmpty()))

    @ExceptionHandler(IllegalStateException::class)
    fun onIllegalState(e: IllegalStateException): ResponseEntity<Map<String, String>> =
        ResponseEntity.status(HttpStatus.NOT_FOUND).body(mapOf("error" to (e.message ?: "not-found")))

    @ExceptionHandler(IllegalArgumentException::class)
    fun onIllegalArgument(e: IllegalArgumentException): ResponseEntity<Map<String, String>> =
        ResponseEntity.status(HttpStatus.BAD_REQUEST).body(mapOf("error" to (e.message ?: "bad-request")))

    companion object {
        private const val STDERR_PREVIEW_CHARS = 2_000
    }
}
