package com.jorisjonkers.personalstack.agentgateway.web

import org.junit.jupiter.api.Test
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.setup.MockMvcBuilders

class GatewayHealthControllerTest {
    private val mockMvc: MockMvc =
        MockMvcBuilders
            .standaloneSetup(HealthController())
            .build()

    @Test
    fun `healthz returns ok`() {
        mockMvc
            .get("/healthz")
            .andExpect {
                status { isOk() }
                jsonPath("$.status") { value("ok") }
            }
    }
}
