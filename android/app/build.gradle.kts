plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.growbuddy.app"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.growbuddy.app"
        minSdk = 26
        targetSdk = 37
        versionCode = 253
        versionName = "2.5.3"
    }

    // App languages = the res/values-<code>/ folders (docs/LOCALIZATION.md). The generated
    // locale config lists them for Android's per-app language setting and the in-app picker.
    androidResources {
        generateLocaleConfig = true
    }

    // Release key lives outside the repo: ~/.config/growbuddy/ plus the GROWBUDDY_RELEASE_*
    // entries in ~/.gradle/gradle.properties (android/README.md › Release build).
    val releaseStore = providers.gradleProperty("GROWBUDDY_RELEASE_STORE_FILE").orNull
    signingConfigs {
        if (releaseStore != null) {
            create("release") {
                storeFile = file(releaseStore)
                storePassword = providers.gradleProperty("GROWBUDDY_RELEASE_STORE_PASSWORD").get()
                keyAlias = providers.gradleProperty("GROWBUDDY_RELEASE_KEY_ALIAS").get()
                keyPassword = providers.gradleProperty("GROWBUDDY_RELEASE_KEY_PASSWORD").get()
            }
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.findByName("release")
            // No R8: under its optimisations Retrofit stops recognising suspend API methods
            // ("Unable to create converter for class java.lang.Object" on the first call).
            // The APK is a bit larger; nothing else changes.
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true // version and build number in Settings › About
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.work.runtime)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.retrofit)
    implementation(libs.retrofit.kotlinx.serialization)
    implementation(libs.okhttp)
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
    implementation(libs.unifiedpush.connector)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
}
