import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kotlin.compose)
}

// Release signing. The keystore never lives in the repo: point at it with
// SAFTLADEN_KEYSTORE and friends, either exported locally or injected by CI.
// When they are absent the release build is simply left unsigned, so a clone
// without the key still builds.
val keystorePath: String? = System.getenv("SAFTLADEN_KEYSTORE")
    ?: providers.gradleProperty("saftladen.keystore").orNull

android {
    namespace = "io.github.hamann.saftladen"
    compileSdk = 35
    // Keep in sync with flake.nix (buildToolsVersion / platformVersions)
    buildToolsVersion = "35.0.1"

    defaultConfig {
        applicationId = "io.github.hamann.saftladen"
        // Karoo 2 runs Android 8.1 (API 27); 26 is the floor for java.time without desugaring.
        minSdk = 26
        targetSdk = 34
        // A Karoo decides an update exists by comparing versionCode, so raise it
        // with every release or an installed extension never sees the new one.
        versionCode = 2
        versionName = "1.0.0"
    }

    signingConfigs {
        if (keystorePath != null) {
            create("release") {
                storeFile = file(keystorePath)
                storePassword = System.getenv("SAFTLADEN_KEYSTORE_PASSWORD")
                    ?: providers.gradleProperty("saftladen.keystore.password").orNull
                keyAlias = System.getenv("SAFTLADEN_KEY_ALIAS")
                    ?: providers.gradleProperty("saftladen.key.alias").orNull
                    ?: "saftladen"
                keyPassword = System.getenv("SAFTLADEN_KEY_PASSWORD")
                    ?: providers.gradleProperty("saftladen.key.password").orNull
            }
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.findByName("release")
            // Off until a release build has been exercised on-device: karoo-ext
            // serialises its models with kotlinx.serialization, whose serializers R8
            // cannot see. proguard-rules.pro keeps them, but that is untested, and
            // the debug build you sideload never minifies.
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.karoo.ext)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.timber)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
