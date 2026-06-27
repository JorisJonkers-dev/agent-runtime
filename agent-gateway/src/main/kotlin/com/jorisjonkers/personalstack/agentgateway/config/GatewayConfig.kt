package com.jorisjonkers.personalstack.agentgateway.config

import com.jorisjonkers.personalstack.agentgateway.tmux.ClaudeTranscriptLocator
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
@EnableConfigurationProperties(GatewayProperties::class)
class GatewayConfig {
    @Bean
    fun claudeTranscriptLocator(): ClaudeTranscriptLocator = ClaudeTranscriptLocator.fromEnvironment()
}
