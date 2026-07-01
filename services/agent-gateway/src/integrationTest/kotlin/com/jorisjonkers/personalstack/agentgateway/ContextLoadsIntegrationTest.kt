package com.jorisjonkers.personalstack.agentgateway

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ApplicationContext

@Tag("integration")
@SpringBootTest(
    properties = [
        "spring.main.web-application-type=none",
        "management.tracing.enabled=false",
    ],
)
class ContextLoadsIntegrationTest(
    private val context: ApplicationContext,
) {
    @Test
    fun springContextBootsWithDefaultConfig() {
        assertThat(context).isNotNull
        assertThat(context.containsBean("agentSessionManager")).isTrue
        assertThat(context.containsBean("tmuxClient")).isTrue
        assertThat(context.containsBean("gitClient")).isTrue
    }
}
