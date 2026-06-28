plugins {
    base
}

group = "dev.jorisjonkers"
version =
    providers
        .gradleProperty("artifactVersion")
        .orElse(
            providers.environmentVariable("GITHUB_REF_NAME").map { ref ->
                if (ref.startsWith("v")) ref.removePrefix("v") else "0.16.0-SNAPSHOT"
            },
        ).orElse("0.16.0-SNAPSHOT")
        .get()

subprojects {
    group = rootProject.group
    version = rootProject.version
}
