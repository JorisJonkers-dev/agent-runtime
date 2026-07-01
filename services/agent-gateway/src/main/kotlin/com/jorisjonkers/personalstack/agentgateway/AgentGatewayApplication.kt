package com.jorisjonkers.personalstack.agentgateway

import org.springframework.boot.SpringApplication
import org.springframework.boot.autoconfigure.SpringBootApplication

@SpringBootApplication
class AgentGatewayApplication

fun main(args: Array<String>) {
    SpringApplication.run(arrayOf(AgentGatewayApplication::class.java), args)
}
