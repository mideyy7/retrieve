import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

ksp {
    // Room writes each schema version here so future migrations can be tested against it.
    arg("room.schemaLocation", "$projectDir/schemas")
}

android {
    namespace = "com.mideyy7.retrieve"
    // compileSdk = APIs we can compile against (AndroidX needs 37+); targetSdk = runtime behaviour we opt into.
    compileSdk {
        version = release(37) { minorApiLevel = 2 }
    }

    defaultConfig {
        applicationId = "com.mideyy7.retrieve"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            // arm64 covers the test phone, almost all Play devices and the Apple-silicon emulator.
            abiFilters += "arm64-v8a"
        }
    }

    ndkVersion = "30.0.16248370"
    externalNativeBuild {
        cmake {
            path = file("../native/CMakeLists.txt")
            version = "4.1.2"
        }
    }

    // Upload key for Play (see docs/IMPLEMENTATION_PLAN.md R1). keystore.properties is gitignored and
    // holds storeFile, storePassword, keyAlias, keyPassword. Without it, release builds are unsigned.
    val keystoreFile = rootProject.file("keystore.properties")
    if (keystoreFile.exists()) {
        val props = Properties().apply { keystoreFile.inputStream().use { load(it) } }
        signingConfigs {
            create("upload") {
                storeFile = rootProject.file(props.getProperty("storeFile"))
                storePassword = props.getProperty("storePassword")
                keyAlias = props.getProperty("keyAlias")
                keyPassword = props.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            // Separate package so debug and release builds (different signing keys) can be
            // installed side by side on the test device.
            applicationIdSuffix = ".debug"
        }
        release {
            signingConfig = signingConfigs.findByName("upload")
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    // The embedding model is memory-mapped from the APK, which needs it stored uncompressed.
    androidResources {
        noCompress += "tflite"
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }

    // Exported Room schemas as debug assets, so MigrationTestHelper (under Robolectric, which reads
    // the debug variant's merged assets) can build old versions. Not included in release builds.
    sourceSets.getByName("debug").assets.directories.add("$projectDir/schemas")
    // Query sets for on-device checks (ParityDumpTest), shared with the desktop scripts.
    sourceSets.getByName("androidTest").assets.directories.add("$rootDir/bench")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.navigation.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.core)
    debugImplementation(libs.compose.ui.tooling)
    // On-device LLM (LiteRT-LM) for answering questions; the model itself is delivered separately.
    implementation(libs.litertlm.android)
    // Hosts composables in an empty Activity for Compose UI tests (Robolectric, on the JVM).
    debugImplementation(libs.compose.ui.test.manifest)

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
    implementation(libs.coroutines.android)
    implementation(libs.pdfbox.android)
    implementation(libs.mlkit.text.recognition)
    implementation(libs.work.runtime.ktx)
    implementation(libs.serialization.json)
    implementation(libs.litert)
    implementation(libs.litert.gpu)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.room.testing)
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.ui.test.junit4)

    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.runner)
}
