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

    // Upload key for Play (see IMPLEMENTATION_PLAN.md R1). keystore.properties is gitignored and
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

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
    implementation(libs.coroutines.android)
    implementation(libs.pdfbox.android)
    implementation(libs.mlkit.text.recognition)
    implementation(libs.work.runtime.ktx)
    implementation(libs.serialization.json)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.coroutines.test)
}
