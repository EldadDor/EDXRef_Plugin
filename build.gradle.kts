import org.jetbrains.changelog.Changelog

plugins {
	id("java")
	id("org.jetbrains.kotlin.jvm") version "2.0.21"
	id("org.jetbrains.intellij") version "1.17.0"
	id("org.jetbrains.changelog") version "2.2.1"
	id("org.jetbrains.qodana") version "2024.1.9"
	id("org.jetbrains.kotlinx.kover") version "0.8.3"
}

group = "com.github.edxref"
version = properties("pluginVersion")


// Set the JVM language level used to build the project
kotlin {
	jvmToolchain(21)
}

repositories {
	mavenCentral()
}

// Configure Gradle IntelliJ Plugin
// Read more: https://plugins.jetbrains.com/docs/intellij/tools-gradle-intellij-plugin.html
intellij {
	version.set("2024.2.3")
	type.set("IC") // Target IDE Platform

	plugins.set(listOf("com.intellij.java", "org.jetbrains.kotlin"))
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
	buildSearchableOptions {
		enabled = false // Often needed for K2 compatibility
	}


	// Set the JVM compatibility versions
	compileKotlin {
		kotlinOptions {
			jvmTarget = "21"
			languageVersion = "2.0"
			apiVersion = "2.0"
//			freeCompilerArgs += listOf(
//				"-Xuse-k2"  // Explicitly enable K2 compiler
//				)
			}
		}
		compileTestKotlin {
				kotlinOptions {
					jvmTarget = "21"
					languageVersion = "2.0"
					apiVersion = "2.0"
//					freeCompilerArgs += listOf(
//						"-Xuse-k2"
//					)
				}
		}
		patchPluginXml {
			sinceBuild.set("242")
			untilBuild.set("252.*")
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

	tasks.register<Copy>("copyJarToDist") {
		group = "build" // Optional: Group the task under the "build" category
		description = "Copies the jar artifact to the dist folder"

		// Define the source file (the jar artifact)
		from(layout.buildDirectory.file("libs/EDXref-${properties("pluginVersion")}.jar"))


		// Define the destination directory
		into(layout.projectDirectory.dir("dist"))

		// Ensure the task runs after the jar is built
		dependsOn(tasks.named("jar"))
	}

	tasks.named("build") {
		finalizedBy("copyJarToDist")
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

	tasks.register("verifyK2Compilation") {
		group = "verification"
		description = "Verifies that plugin compiles correctly with K2 enabled"

		doLast {
			println("Kotlin version: ${KotlinVersion.CURRENT}")
			val compileTask = tasks.getByName("compileKotlin") as org.jetbrains.kotlin.gradle.tasks.KotlinCompile
			println("Language version: ${compileTask.compilerOptions.languageVersion.get()}")
			println("API version: ${compileTask.compilerOptions.apiVersion.get()}")
			println("JVM target: ${compileTask.compilerOptions.jvmTarget.get()}")
		}
		dependsOn("compileKotlin", "compileTestKotlin")
	}

// Add task to check for K2 compatibility issues in your plugin code
// ... existing code ...

// Add task to check for K2 compatibility issues in your plugin code
// ... existing code ...

// Add task to check for K2 compatibility issues in your plugin code
	tasks.register("checkK2Compatibility") {
		group = "verification"
		description = "Checks for common K2 compatibility issues in plugin code"

		doLast {
			val sourceFiles = fileTree("src/main/kotlin") {
				include("**/*.kt")
			}

			var issuesFound = false
			val commonK2Issues = listOf(
				// Look for actual analyze blocks, not comments or strings
				Regex("""^\s*analyze\s*\{""") to "Found analyze block - Consider using direct PSI operations instead for K2 compatibility",
				Regex("""\bresolveToDescriptorIfAny\b""") to "Descriptor-based APIs may not work with K2",
				Regex("""AnalysisHandlerExtension""") to "Analysis handler extensions may need updates for K2",
				Regex("""KtAnalysisSession""") to "Check if KtAnalysisSession usage is compatible with current IntelliJ version",
				// Check for other potential K2 issues
				Regex("""\bBindingContext\b""") to "BindingContext usage may need K2 compatibility updates",
				Regex("""\bResolveSession\b""") to "ResolveSession usage may not be compatible with K2"
			)

			sourceFiles.forEach { file ->
				val lines = file.readLines()
				lines.forEachIndexed { lineIndex, line ->
					// Skip comments and string literals for analyze block check
					val trimmedLine = line.trim()
					if (trimmedLine.startsWith("//") || trimmedLine.startsWith("*") || trimmedLine.startsWith("/*")) {
						return@forEachIndexed
					}

					commonK2Issues.forEach { (regex, message) ->
						if (regex.containsMatchIn(line)) {
							// Special handling for analyze block to avoid false positives
							if (message.contains("analyze block")) {
								// Make sure it's not in a string literal or comment
								if (!line.contains("\"") || line.indexOf(regex.pattern) < line.indexOf("\"")) {
									println("⚠️  K2 compatibility issue in ${file.relativeTo(projectDir)}:${lineIndex + 1}")
									println("   Line: ${line.trim()}")
									println("   Issue: $message")
									println()
									issuesFound = true
								}
							} else {
								println("⚠️  Potential K2 issue in ${file.relativeTo(projectDir)}:${lineIndex + 1}")
								println("   Line: ${line.trim()}")
								println("   Issue: $message")
								println()
								issuesFound = true
							}
						}
					}
				}
			}


// ... existing code ...

			if (!issuesFound) {
				println("✅ No K2 compatibility issues found in source code")
			} else {
				println("📋 Summary: Found potential K2 compatibility issues. Review the suggestions above.")
				println("💡 Tip: Run './gradlew verifyK2Compilation' to test if your code compiles with K2 enabled")
			}
		}
	}

// ... existing code ...

// Update Kotlin compilation to enable K2 explicitly
	tasks.compileKotlin {
		kotlinOptions {
			jvmTarget = "21"
			// Enable K2 compiler explicitly
			freeCompilerArgs += listOf(
				"-Xuse-k2"
			)
		}
	}

	tasks.compileTestKotlin {
		kotlinOptions {
			jvmTarget = "21"
			freeCompilerArgs += listOf(
				"-Xuse-k2"
			)
		}
	}

// ... existing code ...
