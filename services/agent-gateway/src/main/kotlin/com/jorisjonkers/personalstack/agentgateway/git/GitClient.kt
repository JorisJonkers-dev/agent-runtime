package com.jorisjonkers.personalstack.agentgateway.git

import com.jorisjonkers.personalstack.agentgateway.process.ProcessRunner
import org.springframework.stereotype.Component
import java.io.File
import java.nio.file.Files
import kotlin.io.path.Path

/**
 * Wraps git + gh for the runner. All GitHub access goes over HTTPS using
 * the short-lived GitHub App installation token: runner boot configures a
 * git credential helper (`agent-gh-app`) and rewrites SSH remotes to HTTPS,
 * so git child processes started here inherit that config and authenticate
 * through the App installation — no SSH deploy key is involved.
 *
 * `gh` authenticates via a GH_TOKEN env var that agents-api / the runner
 * provide per-Pod, and git push relies on the inherited credential helper.
 */
@Component
class GitClient(
    private val runner: ProcessRunner,
) {
    fun clone(
        repoUrl: String,
        intoDir: String,
        branch: String? = null,
    ): String {
        val target = Path(intoDir)
        if (Files.isDirectory(target.resolve(".git"))) {
            return intoDir
        }
        val argv =
            mutableListOf("git", "clone", "--depth", "50").apply {
                if (branch != null) {
                    add("--branch")
                    add(branch)
                }
                add(repoUrl)
                add(intoDir)
            }
        runner.run(argv, env = cloneEnv(repoUrl), timeoutSeconds = 300)
        return intoDir
    }

    fun checkoutNewBranch(
        repoDir: String,
        branch: String,
    ) {
        runner.run(
            listOf("git", "checkout", "-b", branch),
            cwd = File(repoDir),
        )
    }

    fun push(
        repoDir: String,
        remote: String = "origin",
        branch: String? = null,
    ): String {
        val argv =
            mutableListOf("git", "push", "-u", remote).apply {
                if (branch != null) add(branch) else add("HEAD")
            }
        // No explicit env: the push inherits the Pod environment (REPO_ALLOW,
        // App-token plumbing) and the global credential helper configured at
        // runner boot, which authenticates the HTTPS push.
        return runner.run(argv, cwd = File(repoDir), timeoutSeconds = 300).combined
    }

    fun openPr(
        repoDir: String,
        title: String,
        body: String,
        base: String = "main",
    ): String {
        val argv =
            listOf(
                "gh",
                "pr",
                "create",
                "--title",
                title,
                "--body",
                body,
                "--base",
                base,
            )
        return runner.run(argv, cwd = File(repoDir), env = ghEnv(), timeoutSeconds = 120).stdout.trim()
    }

    fun currentBranch(repoDir: String): String =
        runner
            .run(
                listOf("git", "rev-parse", "--abbrev-ref", "HEAD"),
                cwd = File(repoDir),
            ).stdout
            .trim()

    private fun cloneEnv(repoUrl: String): Map<String, String> =
        buildMap {
            // The credential helper resolves the repo from this when the
            // clone target has no `origin` remote yet.
            put("AGENT_GITHUB_REPO_URL", repoUrl)
            githubSlug(repoUrl)?.let { put("REPO_ALLOW", it) }
        }

    private fun githubSlug(repoUrl: String): String? {
        val normalized =
            when {
                repoUrl.startsWith("git@github.com:") -> repoUrl.removePrefix("git@github.com:")
                repoUrl.startsWith("ssh://git@github.com/") -> repoUrl.removePrefix("ssh://git@github.com/")
                repoUrl.startsWith("https://github.com/") -> repoUrl.removePrefix("https://github.com/")
                repoUrl.startsWith("http://github.com/") -> repoUrl.removePrefix("http://github.com/")
                else -> return null
            }.removeSuffix(".git")
        return normalized.takeIf { it.contains('/') }
    }

    private fun ghEnv(): Map<String, String> {
        val token = System.getenv("GH_TOKEN") ?: System.getenv("GITHUB_TOKEN") ?: ""
        return mapOf("GH_TOKEN" to token)
    }
}
