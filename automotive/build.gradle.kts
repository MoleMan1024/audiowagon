/*
SPDX-FileCopyrightText: 2021-2025 MoleMan1024 <moleman1024dev@gmail.com>
SPDX-License-Identifier: GPL-3.0-or-later
*/
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.google.devtools.ksp)
    alias(libs.plugins.google.gms.services)
    alias(libs.plugins.firebase.crashlytics)
    alias(libs.plugins.androidx.room)
}

android {
    namespace = "de.moleman1024.audiowagon"
    defaultConfig {
        applicationId = "de.moleman1024.audiowagon"
        minSdk = 29
        compileSdk = 34
        //noinspection OldTargetApi
        targetSdk = 34
        versionName = "2.8.13"
        // major * 10000 + minor * 100 + patch
        versionCode = 20813
        resValue("string", "VERSION_NAME", versionName as String)
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildTypes {
        getByName("release") {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        create("emulatorSDCard") {
            initWith(getByName("debug"))
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    buildFeatures {
        buildConfig = true
    }
    room {
        schemaDirectory("$projectDir/schemas")
    }
    packaging {
        resources {
            listOf(
                "META-INF/spring.factories",
                "META-INF/spring.handlers",
                "META-INF/spring.schemas",
                "META-INF/spring.tooling",
                "META-INF/license.txt",
                "META-INF/notice.txt",
                "DebugProbesKt.bin"
            ).forEach {
                excludes += it
            }
        }
    }
    externalNativeBuild {
        cmake {
            path = file("CMakeLists.txt")
        }
        ndkVersion = "27.2.12479018"
    }
    kotlin {
        jvmToolchain(11)
    }
    // Should keep this at Java 11 for compatibility with Android >= 11 (Renault is still using Android 10)
    // https://developer.android.com/build/jdks
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    // for Widget.MaterialComponents.Toolbar and others
    implementation(libs.google.android.material)
    // for preferences via XML and widgets like Seekbar
    implementation(libs.androidx.preference.ktx)
    // for Theme.AppCompat.NoActionBar and others
    implementation(libs.androidx.appcompat)
    // for media version 1
    implementation(libs.androidx.media)
    // Firebase requires a file google-services.json that contains Firebase secrets, not uploaded to version control
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.crashlytics)
    // kotlin extensions for common Android librarires
    implementation(libs.androidx.core.ktx)
    // serialization to json to persist playback queues to disk
    implementation(libs.jetbrains.kotlinx.serialization.json)
    // lifecycles (without ViewModel nor LiveData)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    // room database
    implementation(libs.androidx.room.ktx)
    implementation(libs.androidx.room.runtime)
    ksp(libs.androidx.room.compiler)

    // junit testing framework
    testImplementation(libs.junit)
    // Debugging methods for coroutines
    // This library will not work with Android instrumented tests:
    // https://github.com/Kotlin/kotlinx.coroutines/blob/master/kotlinx-coroutines-debug/README.md#debug-agent-and-android
    testImplementation(libs.kotlinx.coroutines.test)
    // mocking framework
    testImplementation(libs.mockito.core)

    // junit testing framework
    androidTestImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.core.ktx)
    // junit extension APIs
    androidTestImplementation(libs.androidx.test.ext)
    // service test rules
    androidTestImplementation(libs.androidx.test.rules)
    // test GUI fragments (needs to use debugImplementation so that a test intent is added to app under test)
    debugImplementation(libs.androidx.fragment.testing)
    // in-memory filesystem to easily create different mocked USB drives in testcases
    androidTestImplementation(libs.com.google.jimfs)
    // mp3 library
    androidTestImplementation(files("libs/mp3agic-0.9.2-SNAPSHOT.jar"))

}
