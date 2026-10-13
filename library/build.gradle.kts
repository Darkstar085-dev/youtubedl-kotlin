plugins {
    id("com.android.library")
    id("maven-publish")
}

android {
    namespace = "com.sipun.youtubedl_android"
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
    testImplementation("junit:junit:${rootProject.extra["junitVer"]}")
    implementation("com.fasterxml.jackson.core:jackson-databind:${rootProject.extra["jacksonDatabindVer"]}")
    implementation("com.fasterxml.jackson.core:jackson-annotations:${rootProject.extra["jacksonAnnotationsVer"]}")
    implementation("commons-io:commons-io:${rootProject.extra["commonsIoVer"]}")
    implementation("org.apache.commons:commons-compress:${rootProject.extra["commonsCompressVer"]}")
}
