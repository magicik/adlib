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
    implementation(libs.androidx.activity)

    testImplementation(libs.junit)
    testImplementation(libs.mockk)
    testImplementation(libs.turbine)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.org.json)
    testImplementation(project(":ads-testing"))
}

publishing {
    publications {
        register<MavenPublication>("release") {
            groupId = "com.magicik.adlib"
            artifactId = project.name
            version = "0.1.0-SNAPSHOT"
            afterEvaluate { from(components["release"]) }
        }
    }
}
