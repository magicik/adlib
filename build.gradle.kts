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
