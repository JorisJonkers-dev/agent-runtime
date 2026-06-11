package com.jorisjonkers.personalstack.agentgateway

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class AgentGatewayApplication

fun main(args: Array<String>) {
    runApplication<AgentGatewayApplication>(*args)
}
