// The Android Gradle plugin is only put on the classpath when an Android SDK is around (see
// settings.gradle.kts), so the engine in :core can be built and tested on any plain JVM
buildscript {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
    dependencies {
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.0.21")
        if (gradle.startParameter.projectProperties["noAndroid"] == null && rootProject.findProject(":app") != null) {
            classpath("com.android.tools.build:gradle:8.7.3")
        }
    }
}
