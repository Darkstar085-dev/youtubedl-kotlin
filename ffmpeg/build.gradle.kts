plugins {
    id("com.android.library")
    id("maven-publish")
}

android {
    namespace = "com.sipun.ffmpeg"
    compileSdk = 37

    defaultConfig {
        minSdk = 30
        ndk {
            abiFilters += setOf("arm64-v8a")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
    }
}

publishing {
    publications {
        register<MavenPublication>("release") {
            afterEvaluate {
                from(components["release"])
            }
        }
    }
}

dependencies {
    implementation(project(":library"))
    implementation("commons-io:commons-io:${rootProject.extra["commonsIoVer"]}")
}
