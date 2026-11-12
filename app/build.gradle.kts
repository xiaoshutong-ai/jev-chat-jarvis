import java.io.File
import java.io.FileInputStream
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// The Xiaoshutong Zhiyan release key is independent of the upstream JEV key.
// Opt in explicitly with an OUT-OF-REPOSITORY properties file. Never silently
// fall back to a previous application's signing certificate.
val releasePropsPath = System.getenv("JEV_KEYSTORE_PROPS")
    ?.trim()
    ?.takeIf { it.isNotEmpty() }

val releaseProps = Properties().apply {
    releasePropsPath?.let { path ->
        val f = File(path)
        require(f.isFile) { "JEV_KEYSTORE_PROPS does not reference a file" }
        FileInputStream(f).use { load(it) }
        val requiredFields = setOf("storeFile", "storePassword", "keyAlias", "keyPassword")
        require(requiredFields.all { !getProperty(it).isNullOrBlank() }) {
            "JEV_KEYSTORE_PROPS lacks required signing fields"
        }
    }
}

// Explicit opt-in for an x86_64 emulator test build; normal Debug/Release
// continue to package only arm64-v8a, preserving the installed phone baseline.
val debugEmulatorAbi = providers.gradleProperty("jevEmulatorAbi").orNull == "x86_64"

android {
    namespace = "com.jev.probe"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.xiaoshutong.zhiyan"
        minSdk = 30
        targetSdk = 36
        versionCode = 2
        versionName = "1.0.1"

        // Native ABI is selected per build type. Regular phone builds remain
        // arm64-only; an explicit test-only property supports the already
        // installed x86_64 Android emulator, with no extra system image.

    }

    signingConfigs {
        if (releaseProps.isNotEmpty()) {
            create("release") {
                storeFile = file(releaseProps.getProperty("storeFile"))
                storePassword = releaseProps.getProperty("storePassword")
                keyAlias = releaseProps.getProperty("keyAlias")
                keyPassword = releaseProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            // Acceptance builds install beside the upstream release instead of
            // requiring an uninstall that would wipe API keys and local history.
            applicationIdSuffix = ".guofeng"
            versionNameSuffix = "-guofeng-debug"
            ndk {
                abiFilters += if (debugEmulatorAbi) listOf("x86_64") else listOf("arm64-v8a")
            }
        }
        release {
            ndk {
                abiFilters += listOf("arm64-v8a")
            }
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
        }
    }

    // Uncompressed, page-aligned .so files: required for the 16 KB page-size
    // devices Android 15+ ships, and it lets the loader mmap the ML Kit natives
    // instead of unpacking them at install time.
    packaging {
        jniLibs {
            useLegacyPackaging = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    // On-device OCR. The *bundled* Chinese model (not the play-services variant):
    // it works on phones with no Google Play services and needs no model download.
    implementation("com.google.mlkit:text-recognition-chinese:16.0.1")
    testImplementation("junit:junit:4.13.2")
    // Real JVM JSONObject for local unit tests; android.jar provides only throwing stubs.
    testImplementation("org.json:json:20240303")
}
