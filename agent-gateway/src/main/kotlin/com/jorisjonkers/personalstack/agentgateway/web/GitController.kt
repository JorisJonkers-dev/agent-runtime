package com.jorisjonkers.personalstack.agentgateway.web

import com.jorisjonkers.personalstack.agentgateway.config.GatewayProperties
import com.jorisjonkers.personalstack.agentgateway.git.GitClient
import com.jorisjonkers.personalstack.agentgateway.web.dto.CloneRequest
import com.jorisjonkers.personalstack.agentgateway.web.dto.GitOperationResponse
import com.jorisjonkers.personalstack.agentgateway.web.dto.OpenPrRequest
import com.jorisjonkers.personalstack.agentgateway.web.dto.PushRequest
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.nio.file.Path
import kotlin.io.path.Path

@RestController
@RequestMapping("/git")
class GitController(
    private val git: GitClient,
    private val props: GatewayProperties,
) {
    @PostMapping("/clone")
    fun clone(
        @RequestBody req: CloneRequest,
    ): GitOperationResponse {
        val target = req.intoDir ?: defaultWorkspaceFor(req.repoUrl).toString()
        val dir = git.clone(req.repoUrl, target, req.branch)
        return GitOperationResponse(ok = true, output = dir)
    }

    @PostMapping("/push")
    fun push(
        @RequestBody req: PushRequest,
    ): GitOperationResponse {
        val out = git.push(req.repoDir, branch = req.branch)
        return GitOperationResponse(ok = true, output = out)
    }

    @PostMapping("/open-pr")
    fun openPr(
        @RequestBody req: OpenPrRequest,
    ): GitOperationResponse {
        val url = git.openPr(req.repoDir, req.title, req.body, req.base)
        return GitOperationResponse(ok = true, output = url)
    }

    private fun defaultWorkspaceFor(repoUrl: String): Path {
        // git@github.com:owner/repo.git → repo
        val tail = repoUrl.substringAfterLast('/').removeSuffix(".git")
        return Path(props.workspaceRoot).resolve(tail)
    }
}
