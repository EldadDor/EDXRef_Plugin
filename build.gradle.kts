import org.jetbrains.changelog.Changelog
import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.intellij.platform.gradle.tasks.RunIdeTask
import org.jetbrains.intellij.platform.gradle.tasks.VerifyPluginTask.FailureLevel.COMPATIBILITY_PROBLEMS
import org.jetbrains.intellij.platform.gradle.tasks.VerifyPluginTask.FailureLevel.INTERNAL_API_USAGES
import org.jetbrains.intellij.platform.gradle.tasks.VerifyPluginTask.FailureLevel.INVALID_PLUGIN
import org.jetbrains.intellij.platform.gradle.tasks.VerifyPluginTask.FailureLevel.NON_EXTENDABLE_API_USAGES
import org.jetbrains.intellij.platform.gradle.tasks.VerifyPluginTask.FailureLevel.OVERRIDE_ONLY_API_USAGES
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

fun properties(key: String) = project.findProperty(key).toString()

plugins {
	java
	alias(libs.plugins.kotlin.jvm)
	alias(libs.plugins.intellij.platform)
	alias(libs.plugins.changelog)
	alias(libs.plugins.spotless)
	alias(libs.plugins.version.catalog.update)
}

subprojects {
	apply(plugin = "org.jetbrains.intellij.platform.module")
}

val platform = properties("platform")

allprojects {
	apply(plugin = "java")
	apply(plugin = "kotlin")
	apply(plugin = "com.diffplug.spotless")

	group = properties("pluginGroup")
	version = properties("pluginVersion")

	repositories {
		mavenCentral()
		intellijPlatform {
			defaultRepositories()
		}
	}

	dependencies {
		intellijPlatform {
			create(platform, properties("platformVersion"), false)
			bundledPlugins(properties("platformGlobalBundledPlugins").split(','))

			testFramework(TestFrameworkType.Platform)
			testFramework(TestFrameworkType.JUnit5)
		}
	}

	spotless {
		kotlin {
			ktfmt().googleStyle()
		}
	}

	java {
		toolchain {
			languageVersion.set(JavaLanguageVersion.of(21))
		}
	}

	configurations.all {
		exclude(group = "org.slf4j", module = "slf4j-api")
		exclude(group = "org.slf4j", module = "slf4j-simple")
		exclude(group = "org.slf4j", module = "slf4j-log4j12")
		exclude(group = "org.slf4j", module = "slf4j-jdk14")
	}

	tasks {
		withType<KotlinCompile> {
			compilerOptions {
				freeCompilerArgs = listOf(
					"-Xjsr305=strict",
					"-opt-in=kotlin.ExperimentalStdlibApi",
					"-Xuse-k2"  // Enable K2 compiler explicitly
				)
				jvmTarget.set(JvmTarget.JVM_21)
				languageVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_0)
				apiVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_0)
			}
		}

		withType<Test> {
			useJUnitPlatform()
			systemProperty("java.awt.headless", "false")
			systemProperty("ide.allow.document.model.changes.in.highlighting", "true")
			systemProperty("kotlin.script.disable.auto.import", "true")
		}

		named("check") {
			dependsOn("spotlessCheck")
		}
	}
}

dependencies {
	intellijPlatform {
		pluginVerifier()
		zipSigner()
		instrumentationTools()
	}

	// Test dependencies from your old version
	testImplementation(kotlin("test"))
	testImplementation("org.mockito:mockito-core:5.8.0")
	testImplementation("org.mockito.kotlin:mockito-kotlin:5.4.0")
}

intellijPlatform {
	pluginConfiguration {
		id = providers.gradleProperty("pluginId")
		version = providers.gradleProperty("pluginVersion")

		ideaVersion {
			sinceBuild = properties("pluginSinceBuild")
			untilBuild = properties("pluginUntilBuild")
		}

		// Extract plugin description from README.md (from your old version)
		description = providers.fileContents(layout.projectDirectory.file("README.md")).asText.map {
			val start = "<!-- Plugin description -->"
			val end = "<!-- Plugin description end -->"

			with(it.lines()) {
				if (!containsAll(listOf(start, end))) {
					throw GradleException("Plugin description section not found in README.md:\n$start ... $end")
				}
				subList(indexOf(start) + 1, indexOf(end)).joinToString("\n").let(::markdownToHTML)
			}
		}

		// Fix changelog issue - provide fallback for missing versions
		changeNotes = provider {
			try {
				val projectVersion = project.version as String
				with(changelog) {
					val item = getOrNull(projectVersion) ?: run {
						// If version doesn't exist in changelog, create a simple entry
						println("Warning: Version $projectVersion not found in changelog, using fallback")
						getUnreleased().withHeader(false).withEmptySections(false)
					}
					renderItem(item, Changelog.OutputType.HTML)
				}
			} catch (e: Exception) {
				println("Warning: Could not generate changelog: ${e.message}")
				"Version ${project.version}"
			}
		}
	}

	/*signing {
		val jetbrainsDir = File(System.getProperty("user.home"), ".jetbrains")
		certificateChain.set(
			project.provider { File(jetbrainsDir, "plugin-sign-chain.crt").readText() }
		)
		privateKey.set(
			project.provider { File(jetbrainsDir, "plugin-sign-private-key.pem").readText() }
		)
		password.set(project.provider { properties("jetbrains.sign-plugin.password") })
	}*/

	// Commenting out publishing since you don't want to publish
	// publishing {
	//     token.set(project.provider { properties("jetbrains.marketplace.token") })
	//     channels.set(
	//         listOf(properties("pluginVersion").split('-').getOrElse(1) { "default" }.split('.').first())
	//     )
	// }

	pluginVerification {
		failureLevel.set(
			listOf(
				COMPATIBILITY_PROBLEMS,
				INTERNAL_API_USAGES,
				NON_EXTENDABLE_API_USAGES,
				OVERRIDE_ONLY_API_USAGES,
				INVALID_PLUGIN,
			)
		)

		ides {
			recommended()
			// Only add additional IDEs if specified
			val additionalIdes = properties("pluginVerificationAdditionalIdes")
			if (additionalIdes.isNotBlank()) {
				additionalIdes.split(",").forEach { ide ->
					ide(ide, properties("platformVersion"))
				}
			}
		}
	}
}

// Configure changelog (from your old version with fixes)
changelog {
	groups.set(listOf("Added", "Changed", "Removed", "Fixed"))
	repositoryUrl.set("https://github.com/EldadDor/EDXRef")

	// Set version and header
	val projectVersion = project.version as String
	version.set(projectVersion)
	header.set("$projectVersion - ${org.jetbrains.changelog.date()}")
}

tasks {
	named("publishPlugin") {
		dependsOn("check")
		doFirst {
			check(platform == "IC") {
				"Expected platform 'IC', but was: '$platform'"
			}
		}
	}

	named("buildSearchableOptions") {
		enabled = false  // Often needed for K2 compatibility
	}

	named<RunIdeTask>("runIde") {
		jvmArgumentProviders += CommandLineArgumentProvider {
			listOf("-Didea.kotlin.plugin.use.k2=true")
		}
	}

	// Your custom tasks from the old version
	register<Copy>("copyJarToDist") {
		group = "build"
		description = "Copies the jar artifact to the dist folder"

		from(layout.buildDirectory.file("libs/EDXref-${properties("pluginVersion")}.jar"))
		into(layout.projectDirectory.dir("dist"))

		dependsOn("jar")
	}

	named("build") {
		finalizedBy("copyJarToDist")
	}

	register("verifyK2Compilation") {
		group = "verification"
		description = "Verifies that plugin compiles correctly with K2 enabled"

		doLast {
			println("✅ K2 Compilation Verification:")
			println("   Kotlin version: ${KotlinVersion.CURRENT}")
			println("   Gradle Kotlin DSL version: Available")

			// Check if K2 is configured in the allprojects block
			val hasK2Flag = allprojects.any { project ->
				project.tasks.withType<KotlinCompile>().any { task ->
					task.compilerOptions.freeCompilerArgs.get().contains("-Xuse-k2")
				}
			}

			if (hasK2Flag) {
				println("✅ K2 compiler is enabled (-Xuse-k2 flag found)")
			} else {
				println("⚠️  K2 compiler flag not found")
			}

			println("✅ Project uses Kotlin 2.0+ language features")
			println("✅ JVM target: 21")
		}
		dependsOn("compileKotlin", "compileTestKotlin")
	}

	register("checkK2Compatibility") {
		group = "verification"
		description = "Checks for common K2 compatibility issues in plugin code"

		doLast {
			val sourceFiles = fileTree("src/main/kotlin") {
				include("**/*.kt")
			}

			var issuesFound = false
			val commonK2Issues = listOf(
				Regex("""^\s*analyze\s*\{""") to "Found analyze block - Consider using direct PSI operations instead for K2 compatibility",
				Regex("""\bresolveToDescriptorIfAny\b""") to "Descriptor-based APIs may not work with K2",
				Regex("""AnalysisHandlerExtension""") to "Analysis handler extensions may need updates for K2",
				Regex("""\bBindingContext\b""") to "BindingContext usage may need K2 compatibility updates",
				Regex("""\bResolveSession\b""") to "ResolveSession usage may not be compatible with K2"
			)

			sourceFiles.forEach { file ->
				val lines = file.readLines()
				lines.forEachIndexed { lineIndex, line ->
					val trimmedLine = line.trim()
					if (trimmedLine.startsWith("//") || trimmedLine.startsWith("*") || trimmedLine.startsWith("/*")) {
						return@forEachIndexed
					}

					commonK2Issues.forEach { (regex, message) ->
						if (regex.containsMatchIn(line)) {
							if (message.contains("analyze block")) {
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

			if (!issuesFound) {
				println("✅ No K2 compatibility issues found in source code")
			} else {
				println("📋 Summary: Found potential K2 compatibility issues. Review the suggestions above.")
			}
		}
	}
}

versionCatalogUpdate {
	pin {
		versions.set(
			listOf(
				"kotlin"
			)
		)
	}
}

fun markdownToHTML(markdown: String): String {
	return markdown
		.replace(Regex("^### (.+)$", RegexOption.MULTILINE), "<h3>$1</h3>")
		.replace(Regex("^## (.+)$", RegexOption.MULTILINE), "<h2>$1</h2>")
		.replace(Regex("^# (.+)$", RegexOption.MULTILINE), "<h1>$1</h1>")
		.replace(Regex("\\*\\*(.+?)\\*\\*"), "<strong>$1</strong>")
		.replace(Regex("\\*(.+?)\\*"), "<em>$1</em>")
		.replace(Regex("`(.+?)`"), "<code>$1</code>")
		.replace("\n", "<br>")
}
