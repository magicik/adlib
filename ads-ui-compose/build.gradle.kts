plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
    `maven-publish`
}

android {
    namespace = "com.example.adlib.ui.compose"
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
    buildFeatures { compose = true }
    publishing {
        singleVariant("release") { withSourcesJar() }
    }
}

dependencies {
    api(project(":ads-core"))
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
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
