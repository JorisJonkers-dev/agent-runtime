package com.jorisjonkers.personalstack.agentgateway.git

import com.jorisjonkers.personalstack.agentgateway.config.GatewayProperties
import com.jorisjonkers.personalstack.agentgateway.process.ProcessFailedException
import com.jorisjonkers.personalstack.agentgateway.process.ProcessRunner
import com.jorisjonkers.personalstack.agentgateway.process.ProcessTimeoutException
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

class GitClientVerifyTest {
    @TempDir
    lateinit var keyDir: Path

    private val repoUrl = "git@github.com:owner/repo.git"

    private fun props() =
        GatewayProperties(
            workspaceRoot = "/workspace",
            tmux = GatewayProperties.Tmux(socketName = "s", stateDir = "/tmp"),
            cli = GatewayProperties.Cli(claude = "claude", codex = "codex"),
            git = GatewayProperties.Git(deployKeyDir = keyDir.toString()),
        )

    @BeforeEach
    fun stageKey() {
        Files.writeString(keyDir.resolve("private_key"), "FAKE-KEY")
    }

    /**
     * Records every argv it sees and replays scripted results so the probe
     * sequence (ls-remote -> push probe -> push delete) can be asserted
     * without a real remote.
     */
    private class FakeRunner(
        private val results: List<ProcessRunner.Result>,
    ) : ProcessRunner() {
        val calls = mutableListOf<List<String>>()
        val envs = mutableListOf<Map<String, String>>()
        private var idx = 0

        override fun run(
            argv: List<String>,
            cwd: File?,
            env: Map<String, String>,
            timeoutSeconds: Long,
            checked: Boolean,
        ): Result {
            calls.add(argv)
            envs.add(env)
            val result = results[idx.coerceAtMost(results.size - 1)]
            idx++
            if (checked && result.exitCode != 0) throw ProcessFailedException(argv, result)
            return result
        }
    }

    private fun ok(stdout: String = "") = ProcessRunner.Result(0, stdout, "")

    private fun fail(stderr: String) = ProcessRunner.Result(1, "", stderr)

    private val lsRemoteHead =
        "deadbeef00000000000000000000000000000000\tHEAD\n" +
            "deadbeef00000000000000000000000000000000\trefs/heads/main\n"

    @Test
    fun `clone skips an existing checkout`() {
        val target = keyDir.resolve("workspace/repo")
        Files.createDirectories(target.resolve(".git"))
        val runner = FakeRunner(listOf(ok()))

        val result = GitClient(runner, props()).clone(repoUrl, target.toString(), branch = "main")

        assertThat(result).isEqualTo(target.toString())
        assertThat(runner.calls).isEmpty()
    }

    @Test
    fun `clone scopes the app-token helper to the requested repository`() {
        val target = keyDir.resolve("workspace/extra")
        val runner = FakeRunner(listOf(ok()))

        val result =
            GitClient(runner, props()).clone(
                "git@github.com:owner/extra.git",
                target.toString(),
                branch = "main",
            )

        assertThat(result).isEqualTo(target.toString())
        assertThat(runner.calls.single())
            .containsExactly(
                "git",
                "clone",
                "--depth",
                "50",
                "--branch",
                "main",
                "git@github.com:owner/extra.git",
                target.toString(),
            )
        assertThat(runner.envs.single()["AGENT_GITHUB_REPO_URL"]).isEqualTo("git@github.com:owner/extra.git")
        assertThat(runner.envs.single()["REPO_ALLOW"]).isEqualTo("owner/extra")
    }

    @Test
    fun `read ok and write ok with HEAD when no branch given`() {
        val runner = FakeRunner(listOf(ok(lsRemoteHead), ok(), ok()))
        val result = GitClient(runner, props()).verify(repoUrl)

        assertThat(result.read).isTrue
        assertThat(result.write).isTrue
        assertThat(runner.calls[0]).containsExactly("git", "ls-remote", repoUrl)
        // probe push points an EXISTING sha at a throwaway ref, never main
        val probePush = runner.calls[1]
        assertThat(probePush[0]).isEqualTo("git")
        assertThat(probePush[1]).isEqualTo("push")
        assertThat(probePush[3]).startsWith("deadbeef00000000000000000000000000000000:refs/heads/_agent-keycheck-")
        assertThat(probePush[3]).doesNotContain("refs/heads/main")
        // delete must always run
        val deletePush = runner.calls[2]
        assertThat(deletePush[3]).startsWith(":refs/heads/_agent-keycheck-")
    }

    @Test
    fun `read ok write denied still deletes probe ref`() {
        val runner = FakeRunner(listOf(ok(lsRemoteHead), fail("remote: Permission to owner/repo.git denied"), ok()))
        val result = GitClient(runner, props()).verify(repoUrl)

        assertThat(result.read).isTrue
        assertThat(result.write).isFalse
        assertThat(result.detail).contains("denied")
        assertThat(runner.calls).hasSize(3)
        assertThat(runner.calls[2][3]).startsWith(":refs/heads/_agent-keycheck-")
    }

    @Test
    fun `read denied skips write probe entirely`() {
        val runner = FakeRunner(listOf(fail("Permission denied (publickey).")))
        val result = GitClient(runner, props()).verify(repoUrl)

        assertThat(result.read).isFalse
        assertThat(result.write).isFalse
        assertThat(runner.calls).hasSize(1)
    }

    @Test
    fun `probe ref is deleted even when probe push throws`() {
        val throwing =
            object : ProcessRunner() {
                val calls = mutableListOf<List<String>>()
                private var idx = 0

                override fun run(
                    argv: List<String>,
                    cwd: File?,
                    env: Map<String, String>,
                    timeoutSeconds: Long,
                    checked: Boolean,
                ): Result {
                    calls.add(argv)
                    idx++
                    return when (idx) {
                        1 -> Result(0, lsRemoteHead, "")
                        2 -> throw ProcessTimeoutException("boom during push")
                        else -> Result(0, "", "")
                    }
                }
            }

        runCatching { GitClient(throwing, props()).verify(repoUrl) }
        // ls-remote, probe push (threw), delete push
        assertThat(throwing.calls).hasSize(3)
        assertThat(throwing.calls[2][3]).startsWith(":refs/heads/_agent-keycheck-")
    }

    @Test
    fun `targets the requested branch tip not HEAD`() {
        val branchSha = "cafe000000000000000000000000000000000000"
        val lsRemoteBranch = "$branchSha\trefs/heads/feature\n"
        val runner = FakeRunner(listOf(ok(lsRemoteBranch), ok(), ok()))
        GitClient(runner, props()).verify(repoUrl, branch = "feature")

        assertThat(runner.calls[0]).containsExactly("git", "ls-remote", repoUrl, "feature")
        assertThat(runner.calls[1][3]).startsWith("$branchSha:refs/heads/_agent-keycheck-")
    }
}
