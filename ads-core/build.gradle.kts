plugins {
    alias(libs.plugins.android.library)

    `maven-publish`
}

android {
    namespace = "com.example.adlib.core"
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
    api(libs.ads.mobile.sdk)
    api(libs.google.ump)
    api(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.mockk)
    testImplementation(libs.turbine)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.org.json)
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
