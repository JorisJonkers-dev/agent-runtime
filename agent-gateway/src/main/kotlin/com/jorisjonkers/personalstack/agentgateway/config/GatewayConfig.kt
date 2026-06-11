package com.jorisjonkers.personalstack.agentgateway.config

import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration

@Configuration
@EnableConfigurationProperties(GatewayProperties::class)
class GatewayConfig
