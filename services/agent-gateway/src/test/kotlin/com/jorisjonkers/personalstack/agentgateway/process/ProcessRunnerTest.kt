package com.jorisjonkers.personalstack.agentgateway.process

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.DisabledOnOs
import org.junit.jupiter.api.condition.OS

class ProcessRunnerTest {
    private val runner = ProcessRunner()

    @Test
    @DisabledOnOs(OS.WINDOWS)
    fun `run captures stdout and exit code`() {
        val result = runner.run(listOf("/bin/sh", "-c", "echo hello"))
        assertThat(result.exitCode).isZero
        assertThat(result.stdout.trim()).isEqualTo("hello")
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    fun `non-zero exit throws ProcessFailedException by default`() {
        assertThatThrownBy { runner.run(listOf("/bin/sh", "-c", "exit 7")) }
            .isInstanceOf(ProcessFailedException::class.java)
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    fun `unchecked mode returns the failed result instead of throwing`() {
        val result = runner.run(listOf("/bin/sh", "-c", "exit 7"), checked = false)
        assertThat(result.exitCode).isEqualTo(7)
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    fun `timeout fires and reports ProcessTimeoutException`() {
        assertThatThrownBy {
            runner.run(listOf("/bin/sh", "-c", "sleep 5"), timeoutSeconds = 1)
        }.isInstanceOf(ProcessTimeoutException::class.java)
    }
}
