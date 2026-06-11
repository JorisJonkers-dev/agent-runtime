package com.jorisjonkers.personalstack.agentgateway.git

import com.jorisjonkers.personalstack.agentgateway.config.GatewayProperties
import com.jorisjonkers.personalstack.agentgateway.process.ProcessRunner
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlin.io.path.Path

/**
 * Wraps git + gh for the runner. The deploy key lives at
 * `agent-gateway.git.deploy-key-dir/private_key`; this client
 * materialises it into a private file with 0600 and exports
 * GIT_SSH_COMMAND so SSH operations can use it without polluting
 * ~/.ssh. Runner boot also rewrites GitHub SSH remotes to HTTPS,
 * so live clones carry a one-repo App-token allow-list for the
 * credential helper.
 *
 * `gh` is used for the PR open step because issuing a PAT for the
 * agent would be a wider blast radius than the per-repo deploy key
 * needs to cover — gh authenticates via a GH_TOKEN env var that
 * agents-api injects per-Pod from a scoped fine-grained token.
 */
@Component
class GitClient(
    private val runner: ProcessRunner,
    private val props: GatewayProperties,
) {
    private val log = LoggerFactory.getLogger(GitClient::class.java)

    companion object {
        private const val DETAIL_CHARS = 500
        private const val RANDOM_SUFFIX_CHARS = 8
    }

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
        return runner.run(argv, cwd = File(repoDir), env = sshEnv(), timeoutSeconds = 300).combined
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

    data class VerifyResult(
        val read: Boolean,
        val write: Boolean,
        val detail: String,
    )

    /**
     * Probes deploy-key access without mutating the repo. Read is a plain
     * `ls-remote`; write points a throwaway ref at an EXISTING commit and
     * deletes it immediately, so no new objects are created and the default
     * branch is never touched. An auth/permission denial is a legitimate
     * {read|write:false} result, not an error — only genuinely unexpected
     * failures (timeouts, malformed remote) surface as detail text.
     */
    fun verify(
        repoUrl: String,
        branch: String? = null,
    ): VerifyResult {
        val env = sshEnv()
        val lsArgs = mutableListOf("git", "ls-remote", repoUrl).apply { if (branch != null) add(branch) }
        val ls = runner.run(lsArgs, env = env, timeoutSeconds = 60, checked = false)
        if (ls.exitCode != 0) {
            val detail = "ls-remote failed: ${ls.combined.trim().take(DETAIL_CHARS)}"
            return VerifyResult(read = false, write = false, detail = detail)
        }

        val sha =
            tipSha(ls.stdout, branch)
                ?: return VerifyResult(
                    read = true,
                    write = false,
                    detail = "read ok; no ref found to probe write against",
                )

        val (writeOk, writeDetail) = probeWrite(repoUrl, sha, env)
        return VerifyResult(read = true, write = writeOk, detail = "read ok; $writeDetail")
    }

    private fun probeWrite(
        repoUrl: String,
        sha: String,
        env: Map<String, String>,
    ): Pair<Boolean, String> {
        val probeRef = "refs/heads/_agent-keycheck-${randomSuffix()}"
        try {
            val push =
                runner.run(
                    listOf("git", "push", repoUrl, "$sha:$probeRef"),
                    env = env,
                    timeoutSeconds = 60,
                    checked = false,
                )
            val ok = push.exitCode == 0
            return ok to if (ok) "write ok" else "write denied: ${push.combined.trim().take(DETAIL_CHARS)}"
        } finally {
            deleteProbeRef(repoUrl, probeRef, env)
        }
    }

    private fun tipSha(
        lsRemoteStdout: String,
        branch: String?,
    ): String? {
        val lines =
            lsRemoteStdout
                .lineSequence()
                .mapNotNull { line ->
                    val parts = line.trim().split('\t', limit = 2)
                    if (parts.size == 2 && parts[0].isNotBlank()) parts[0] to parts[1] else null
                }.toList()
        if (lines.isEmpty()) return null
        val wantRef = branch?.let { "refs/heads/$it" }
        return when {
            wantRef != null -> lines.firstOrNull { it.second == wantRef }?.first
            else -> lines.firstOrNull { it.second == "HEAD" }?.first ?: lines.first().first
        }
    }

    private fun deleteProbeRef(
        repoUrl: String,
        probeRef: String,
        env: Map<String, String>,
    ) {
        runCatching {
            runner.run(
                listOf("git", "push", repoUrl, ":$probeRef"),
                env = env,
                timeoutSeconds = 60,
                checked = false,
            )
        }.onFailure { log.warn("failed to delete probe ref {}: {}", probeRef, it.message) }
    }

    private fun randomSuffix(): String =
        java.util.UUID
            .randomUUID()
            .toString()
            .substring(0, RANDOM_SUFFIX_CHARS)

    fun currentBranch(repoDir: String): String =
        runner
            .run(
                listOf("git", "rev-parse", "--abbrev-ref", "HEAD"),
                cwd = File(repoDir),
            ).stdout
            .trim()

    private fun sshEnv(): Map<String, String> {
        val key = ensureDeployKey()
        val known = Path(props.git.deployKeyDir).resolve("known_hosts").toFile()
        val sshOpts =
            buildString {
                append("ssh -i ${key.toAbsolutePath()} -o IdentitiesOnly=yes")
                if (known.exists()) append(" -o UserKnownHostsFile=${known.absolutePath}")
            }
        return mapOf("GIT_SSH_COMMAND" to sshOpts)
    }

    private fun cloneEnv(repoUrl: String): Map<String, String> =
        sshEnv().toMutableMap().also { env ->
            env["AGENT_GITHUB_REPO_URL"] = repoUrl
            githubSlug(repoUrl)?.let { env["REPO_ALLOW"] = it }
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

    private fun ensureDeployKey(): Path {
        val source = Path(props.git.deployKeyDir).resolve("private_key")
        if (!Files.exists(source)) {
            error("deploy key missing at $source — agents-api should have projected it")
        }
        // Stash in /tmp with 0600 — Secret-mounted files are owned by
        // root and have permissive default modes that openssh refuses.
        val target = Path("/tmp/agent-deploy-key")
        if (!Files.exists(target) || Files.size(target) != Files.size(source)) {
            Files.copy(source, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        }
        runCatching {
            Files.setPosixFilePermissions(target, PosixFilePermissions.fromString("rw-------"))
        }.onFailure { log.warn("could not chmod 0600 deploy key: {}", it.message) }
        return target
    }
}
