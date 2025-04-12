import org.jetbrains.changelog.Changelog

plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "1.9.25"
    id("org.jetbrains.intellij") version "1.17.0"
    id("org.jetbrains.changelog") version "2.2.1"
    id("org.jetbrains.qodana") version "2024.1.9"
    id("org.jetbrains.kotlinx.kover") version "0.8.3"
}

group = "com.github.edxref"
version = "1.0.0"

// Set the JVM language level used to build the project
kotlin {
    jvmToolchain(17)
}

repositories {
    mavenCentral()
}

// Configure Gradle IntelliJ Plugin
// Read more: https://plugins.jetbrains.com/docs/intellij/tools-gradle-intellij-plugin.html
intellij {
    version.set("2023.3.7")
    type.set("IC") // Target IDE Platform

    plugins.set(listOf("com.intellij.java","org.jetbrains.kotlin"))
}

// Configure Gradle Changelog Plugin
changelog {
    groups.empty()
    repositoryUrl.set("https://github.com/EldadDor/EDXRef")
}

dependencies {
    testImplementation(kotlin("test"))
    testImplementation("org.mockito:mockito-core:4.6.1")
    testImplementation("org.mockito.kotlin:mockito-kotlin:4.0.0") // For Kotlin-specific features
}

tasks {
    // Set the JVM compatibility versions
    compileKotlin {
        kotlinOptions.jvmTarget = "17"
    }
    compileTestKotlin {
        kotlinOptions.jvmTarget = "17"
    }

    patchPluginXml {
        sinceBuild.set("233")
        untilBuild.set("243.*")

        // Extract the <!-- Plugin description --> section from README.md
        pluginDescription.set(
            file("README.md").readText().lines().run {
                val start = "<!-- Plugin description -->"
                val end = "<!-- Plugin description end -->"

                if (!containsAll(listOf(start, end))) {
                    throw GradleException("Plugin description section not found in README.md:\n$start ... $end")
                }
                subList(indexOf(start) + 1, indexOf(end)).joinToString("\n")
            }.let { org.jetbrains.changelog.markdownToHTML(it) }
        )


        // Get the latest available change notes from the changelog file
        changeNotes.set(provider {
            with(changelog) {
                getOrNull(version.get()) ?: getUnreleased()
            }.toHTML()
        })
    }

    signPlugin {
        certificateChain.set(System.getenv("CERTIFICATE_CHAIN"))
        privateKey.set(System.getenv("PRIVATE_KEY"))
        password.set(System.getenv("PRIVATE_KEY_PASSWORD"))
    }

    publishPlugin {
        token.set(System.getenv("PUBLISH_TOKEN"))
        channels.set(
            providers.provider<Iterable<String>> {
                // Break out the version parsing to improve type inference
                val version = properties("pluginVersion")
                val preRelease = version.split('-').getOrElse(1) { "default" }
                // Either use first() or [0]. Using [0] can sometimes help:
                val channel = preRelease.split('.')[0]
                listOf(channel)
            }
        )
    }
}

tasks.test {
    systemProperty("ide.allow.document.model.changes.in.highlighting", "true")
    systemProperty("kotlin.script.disable.auto.import", "true")
}
// Configure Gradle Kover Plugin - read more: https://github.com/Kotlin/kotlinx-kover#configuration
kover {
    reports {
        total {
            xml {
                onCheck = true
            }
        }
    }
}

// Helper function for getting properties
fun properties(key: String) = providers.gradleProperty(key).getOrElse("")