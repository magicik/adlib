plugins {
    alias(libs.plugins.android.library)

    `maven-publish`
}

android {
    namespace = "com.example.adlib.testing"
    compileSdk {
        version = release(37) {
            minorApiLevel = 1
        }
    }
    defaultConfig {
        minSdk = 24
        consumerProguardFiles("consumer-rules.pro")
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    publishing {
        singleVariant("release") { withSourcesJar() }
    }
}

dependencies {
    api(project(":ads-core"))
    api(libs.kotlinx.coroutines.test)

    testImplementation(libs.junit)
    testImplementation(libs.turbine)
}

// TODO(publish): groupId must become com.github.<jitpack-user> (see README). Not published for real yet.
publishing {
    publications {
        register<MavenPublication>("release") {
            groupId = "com.github.PLACEHOLDER"
            artifactId = project.name
            version = "0.1.0-SNAPSHOT"
            afterEvaluate { from(components["release"]) }
        }
    }
}
