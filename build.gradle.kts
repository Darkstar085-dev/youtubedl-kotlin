// Shared build configuration for the youtubedl-kotlin modules.
buildscript {
    val kotlinVersion = "2.4.20"
    repositories {
        google()
        mavenCentral()
    }
    dependencies {
        classpath("com.android.tools.build:gradle:9.4.1")
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:$kotlinVersion")
        classpath("org.jetbrains.kotlin:compose-compiler-gradle-plugin:$kotlinVersion")
    }
}

allprojects {
    group = "io.github.darkstar085.youtubedl-kotlin"
    version = "1.0.1"
}

extra.apply {
    set("junitVer", "4.13.2")
    set("jacksonDatabindVer", "2.22.3")
    set("jacksonAnnotationsVer", "2.22")
    set("commonsIoVer", "2.22.0")
    set("commonsCompressVer", "1.28.0")
}

tasks.register<Delete>("clean") {
    delete(rootProject.layout.buildDirectory)
}
