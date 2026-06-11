plugins {
    alias(libs.plugins.extratoast.spring)
    alias(libs.plugins.extratoast.detekt)
    alias(libs.plugins.extratoast.ktlint)
    alias(libs.plugins.extratoast.testing)
}

dependencies {
    implementation(libs.kotlin.commons.observability)
    implementation(libs.kotlin.commons.timing)
    implementation(libs.kotlin.commons.web)
    implementation("org.springframework:spring-aop")
    implementation("org.aspectj:aspectjweaver:1.9.25.1")
    implementation("org.springframework.boot:spring-boot-starter-websocket")
    // Tracing runtime jars — same shape as auth-api / agents-api so
    // TimingAutoConfiguration in kotlin-commons-timing activates.
    runtimeOnly("io.micrometer:micrometer-tracing-bridge-otel")
    runtimeOnly("io.opentelemetry:opentelemetry-exporter-otlp")
    testImplementation("org.awaitility:awaitility:4.3.0")
}

// agent-gateway is the only service in the monorepo whose hot path is
// process exec (tmux / git / ssh) — every meaningful test for those
// classes needs the real binaries on PATH, which is the integration
// image's job. The exclusions below keep the 80 % jacoco bar honest
// for the classes that *are* unit-testable (process abstraction, the
// in-memory session registry, controllers via MockMvc, the log
// tailer, the WS envelope parser) and acknowledge that TmuxClient /
// GitClient / AgentAttachHandler are covered by container-level
// integration tests in the system-tests module rather than here.
// The Spring Boot main class is excluded by the testing convention for
// every service via the `**/*Application*` defaults.
@Suppress("UNCHECKED_CAST")
(extensions.getByName("jacocoExclusionPatterns") as ListProperty<String>).addAll(
    // Trailing `*.class` (not `.class`) sweeps any future Kotlin-inner
    // companions; the outer-class-only form silently drops `Outer$Inner`
    // entries from the exclusion.
    "**/tmux/TmuxClient*.class",
    "**/git/GitClient*.class",
    "**/ws/AgentAttachHandler*.class",
)
