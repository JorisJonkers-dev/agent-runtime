package com.jorisjonkers.personalstack.agentgateway.config

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource

class GatewayPropertiesBindingTest {
    @Test
    fun `runner identity binds from injected pod environment`() {
        val props =
            bind(
                mapOf(
                    "agent-gateway.workspace-root" to "/workspace",
                    "agent-gateway.tmux.socket-name" to "agent-gw",
                    "agent-gateway.tmux.state-dir" to "/tmp/agent-gateway",
                    "agent-gateway.cli.claude" to "claude",
                    "agent-gateway.cli.codex" to "codex",
                    "agent-gateway.runner.setup-id" to "gpu",
                    "agent-gateway.runner.setup-version" to "7",
                    "agent-gateway.runner.setup-hash" to "abc123",
                    "agent-gateway.runner.generation" to "42",
                ),
            )

        assertThat(props.runner.setupId).isEqualTo("gpu")
        assertThat(props.runner.setupVersion).isEqualTo(7L)
        assertThat(props.runner.setupHash).isEqualTo("abc123")
        assertThat(props.runner.generation).isEqualTo(42L)
    }

    private fun bind(properties: Map<String, String>): GatewayProperties {
        val source = MapConfigurationPropertySource(properties)
        return Binder(source)
            .bind("agent-gateway", GatewayProperties::class.java)
            .get()
    }
}
