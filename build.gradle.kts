plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.compose) apply false
}

// scripts/test-quick.sh runs `testDebugUnitTest --tests <pattern>` across all modules;
// modules with no matching test must not fail the run.
subprojects {
    tasks.withType<Test>().configureEach {
        filter.isFailOnNoMatchingTests = false
    }
}

// ktlint: plain CLI via JavaExec (see libs.versions.toml for why not the Gradle plugin).
val ktlintCli: Configuration by configurations.creating
dependencies {
    ktlintCli(libs.ktlint.cli) { artifact { classifier = "all" } }
}

val ktlintTargets = listOf("**/src/**/*.kt", "**/*.gradle.kts", "!**/build/**")

tasks.register<JavaExec>("ktlintCheck") {
    group = "verification"
    description = "Runs ktlint on all Kotlin sources and Gradle scripts."
    classpath = ktlintCli
    mainClass.set("com.pinterest.ktlint.Main")
    args(ktlintTargets)
}

tasks.register<JavaExec>("ktlintFormat") {
    group = "formatting"
    description = "Auto-formats all Kotlin sources and Gradle scripts with ktlint."
    classpath = ktlintCli
    mainClass.set("com.pinterest.ktlint.Main")
    args(listOf("--format") + ktlintTargets)
}
