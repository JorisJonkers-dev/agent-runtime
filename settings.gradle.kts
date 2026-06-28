pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        maven {
            name = "JorisJonkersDevGradleConventions"
            url = uri("https://maven.pkg.github.com/JorisJonkers-dev/gradle-conventions")
            credentials {
                username =
                    providers
                        .gradleProperty("gpr.user")
                        .orElse(providers.environmentVariable("GITHUB_ACTOR"))
                        .orNull
                password =
                    providers
                        .gradleProperty("gpr.token")
                        .orElse(providers.environmentVariable("GITHUB_TOKEN"))
                        .orNull
            }
        }
    }
    resolutionStrategy {
        eachPlugin {
            val id = requested.id.id
            if (id.startsWith("dev.jorisjonkers.")) {
                useModule(
                    "dev.jorisjonkers:gradle-conventions-${id.removePrefix("dev.jorisjonkers.")}:${requested.version}",
                )
            }
        }
    }
}

rootProject.name = "agent-runtime"

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        maven("https://repo.spring.io/milestone")
        maven {
            name = "JorisJonkersDevKotlinSpringCommons"
            url = uri("https://maven.pkg.github.com/JorisJonkers-dev/kotlin-spring-commons")
            credentials {
                username =
                    providers
                        .gradleProperty("gpr.user")
                        .orElse(providers.environmentVariable("GITHUB_ACTOR"))
                        .orNull
                password =
                    providers
                        .gradleProperty("gpr.token")
                        .orElse(providers.environmentVariable("GITHUB_TOKEN"))
                        .orNull
            }
        }
    }
}

include(":services:agent-gateway")
