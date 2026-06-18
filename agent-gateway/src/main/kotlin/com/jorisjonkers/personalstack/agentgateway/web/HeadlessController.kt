package com.jorisjonkers.personalstack.agentgateway.web

import com.jorisjonkers.personalstack.agentgateway.headless.HeadlessJob
import com.jorisjonkers.personalstack.agentgateway.headless.HeadlessJobManager
import com.jorisjonkers.personalstack.agentgateway.headless.HeadlessOutputStreamer
import com.jorisjonkers.personalstack.agentgateway.web.dto.HeadlessJobResponse
import com.jorisjonkers.personalstack.agentgateway.web.dto.HeadlessRequest
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter

@RestController
@RequestMapping("/agents/headless")
class HeadlessController(
    private val jobs: HeadlessJobManager,
) {
    @PostMapping
    fun launch(
        @RequestBody req: HeadlessRequest,
    ): ResponseEntity<HeadlessJobResponse> {
        val job =
            jobs.launch(
                kind = req.kind,
                prompt = req.prompt,
                workspacePath = req.workspacePath,
                cliSessionId = req.cliSessionId,
                timeoutSeconds = req.timeoutSeconds ?: HeadlessJobManager.DEFAULT_TIMEOUT_SECONDS,
            )
        return ResponseEntity.status(HttpStatus.CREATED).body(toResponse(job, null))
    }

    @GetMapping("/{id}")
    fun get(
        @PathVariable id: String,
    ): ResponseEntity<HeadlessJobResponse> {
        val job = jobs.get(id) ?: return ResponseEntity.notFound().build()
        val output =
            if (job.status != com.jorisjonkers.personalstack.agentgateway.headless.HeadlessJobStatus.RUNNING) {
                jobs.readOutput(id)
            } else {
                null
            }
        return ResponseEntity.ok(toResponse(job, output))
    }

    @GetMapping("/{id}/stream", produces = [MediaType.TEXT_EVENT_STREAM_VALUE])
    fun stream(
        @PathVariable id: String,
    ): ResponseEntity<SseEmitter> {
        val job = jobs.get(id) ?: return ResponseEntity.notFound().build()
        val emitter = SseEmitter(HeadlessOutputStreamer.TIMEOUT_MILLIS)
        HeadlessOutputStreamer(
            job = job,
            currentJob = jobs::get,
            emitter = emitter,
        ).start()
        return ResponseEntity
            .ok()
            .contentType(MediaType.TEXT_EVENT_STREAM)
            .header("Cache-Control", "no-cache")
            .header("X-Accel-Buffering", "no")
            .body(emitter)
    }

    @DeleteMapping("/{id}")
    fun cancel(
        @PathVariable id: String,
    ): ResponseEntity<Void> =
        if (jobs.cancel(id)) ResponseEntity.noContent().build() else ResponseEntity.notFound().build()

    private fun toResponse(
        job: HeadlessJob,
        output: String?,
    ) = HeadlessJobResponse(
        id = job.id,
        kind = job.kind,
        status = job.status,
        exitCode = job.exitCode,
        output = output,
        createdAt = job.createdAt.toString(),
        completedAt = job.completedAt?.toString(),
    )
}
