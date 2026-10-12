plugins {
    id("com.android.library")
}

android {
    namespace = "com.sipun.aria2c"
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
}

dependencies {
    implementation(project(":library"))
    implementation("commons-io:commons-io:${rootProject.extra["commonsIoVer"]}")
}
