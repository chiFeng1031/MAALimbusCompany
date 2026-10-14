import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

/** Copies interface.json, the pipelines and the template images from the resource project into the APK */
abstract class SyncPiAssets : DefaultTask() {
    @get:InputDirectory
    abstract val resourceRoot: DirectoryProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @get:Inject
    abstract val fs: FileSystemOperations

    @TaskAction
    fun sync() {
        fs.sync {
            from(resourceRoot) {
                include("interface.json", "resource/pipeline/**", "resource/image/**")
            }
            into(outputDir.dir("pi"))
        }
    }
}

val syncPiAssets = tasks.register<SyncPiAssets>("syncPiAssets") {
    resourceRoot.set(rootProject.layout.projectDirectory.dir("../assets"))
    outputDir.set(layout.buildDirectory.dir("generated/piAssets"))
}

val runNumber = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
val keystorePath: String? = System.getenv("KEYSTORE_PATH")?.takeIf { it.isNotBlank() }

android {
    namespace = "io.github.chifeng1031.limbushelper"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.chifeng1031.limbushelper"
        minSdk = 29
        targetSdk = 35
        versionCode = runNumber
        versionName = "0.1.$runNumber"
        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    signingConfigs {
        if (keystorePath != null) {
            create("release") {
                storeFile = file(keystorePath)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Without release keys the APK is still installable, signed with the debug key
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources.excludes += "META-INF/*.kotlin_module"
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(syncPiAssets, SyncPiAssets::outputDir)
    }
}

dependencies {
    implementation(project(":core"))
    implementation("org.opencv:opencv:4.10.0")
}
