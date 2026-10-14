import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("org.jetbrains.kotlin.jvm")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    // org.opencv.* API: the Android app supplies org.opencv:opencv (AAR), JVM tests use openpnp
    compileOnly("org.openpnp:opencv:4.9.0-0")

    testImplementation("org.openpnp:opencv:4.9.0-0")
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
    // repo root, so tests can read assets/ and the AALC reference images
    systemProperty("repoRoot", rootProject.projectDir.parentFile.absolutePath)
    testLogging {
        events("passed", "failed")
        showStandardStreams = true
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
