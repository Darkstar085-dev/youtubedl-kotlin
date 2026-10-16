plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.sipun.youtubedl_demo"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.sipun.youtubedl_demo"
        minSdk = 30
        ndk {
            abiFilters += setOf("arm64-v8a")
        }
        targetSdk = 37
        versionCode = 2
        versionName = "1.0.1"
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
            keepDebugSymbols += setOf(
                "**/libpython.zip.so",
                "**/libaria2c.zip.so",
                "**/libffmpeg.zip.so"
            )
        }
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(project(":library"))
    implementation(project(":ffmpeg"))
    implementation(project(":aria2c"))
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation(platform("androidx.compose:compose-bom:2026.09.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    implementation("io.coil-kt.coil3:coil-compose:3.6.3")
    implementation("io.coil-kt.coil3:coil-network-okhttp:3.6.3")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
