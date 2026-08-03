@file:Suppress("UnstableApiUsage")

import com.android.build.gradle.tasks.PackageAndroidArtifact
import org.jetbrains.kotlin.util.removeSuffixIfPresent
import java.util.Properties

plugins {
    id("com.android.application")
    kotlin("android")
    kotlin("plugin.parcelize")
    id("com.google.devtools.ksp")
}

android {
    val releaseType = readProperties(file("../package.properties")).getProperty("releaseType")
    if (releaseType.contains("\"")) {
        throw IllegalArgumentException("releaseType must not contain \"")
    }

    namespace = "org.akanework.gramophone"
    compileSdk = 36
    buildToolsVersion = "36.0.0"
    ndkVersion = "28.0.13004108"

    androidResources {
        generateLocaleConfig = true
    }

    buildFeatures {
        buildConfig = true
    }

    packaging {
        jniLibs {
            useLegacyPackaging = false
        }
        dex {
            useLegacyPackaging = false
        }
        resources {
            // https://stackoverflow.com/a/58956288
            excludes += "META-INF/*.version"
            // https://github.com/Kotlin/kotlinx.coroutines?tab=readme-ov-file#avoiding-including-the-debug-infrastructure-in-the-resulting-apk
            excludes += "DebugProbesKt.bin"
            // https://issueantenna.com/repo/kotlin/kotlinx.coroutines/issues/3158
            excludes += "kotlin-tooling-metadata.json"

            excludes += "META-INF/**/LICENSE.txt"
        }
    }

    defaultConfig {
        applicationId = "uk.akane.accord"
        // Reasons to not support KK include me.zhanghai.android.fastscroll, WindowInsets for
        // bottom sheet padding, ExoPlayer requiring multidex for KK and poor SD card support
        // That said, supporting Android 5.0 barely costs any tech debt and we plan to keep support
        // for it for a while.
        // Bye bye android 12 - cuz blur
        minSdk = 31
        targetSdk = 36
        versionCode = 18
        versionName = "beta2"
        buildConfigField(
            "String",
            "MY_VERSION_NAME",
            "\"Beta 2\""
        )
        buildConfigField(
            "String",
            "RELEASE_TYPE",
            "\"$releaseType\""
        )
        setProperty("archivesBaseName", "Accord-$versionName")
    }

    signingConfigs {
        create("release") {
            if (project.hasProperty("AKANE_RELEASE_KEY_ALIAS")) {
                storeFile = file(project.properties["AKANE_RELEASE_STORE_FILE"].toString())
                storePassword = project.properties["AKANE_RELEASE_STORE_PASSWORD"].toString()
                keyAlias = project.properties["AKANE_RELEASE_KEY_ALIAS"].toString()
                keyPassword = project.properties["AKANE_RELEASE_KEY_PASSWORD"].toString()
            }
        }
    }

    splits.abi {
        // Enables building multiple APKs per ABI.
        isEnable = true

        // By default all ABIs are included, so use reset() and include to specify that you only
        // want APKs for x86 and x86_64.

        // Resets the list of ABIs for Gradle to create APKs for to none.
        reset()

        // Specifies a list of ABIs for Gradle to create APKs for.
        include("armeabi-v7a", "arm64-v8a", "x86", "x86_64")

        // Specifies that you don't want to also generate a universal APK that includes all ABIs.
        isUniversalApk = true
    }

    buildTypes {
        release {
            if (releaseType != "Profile") {
                isMinifyEnabled = true
                isShrinkResources = true
                proguardFiles(
                    getDefaultProguardFile("proguard-android-optimize.txt"),
                    "proguard-rules.pro"
                )
            } else {
                isMinifyEnabled = false
                isProfileable = true
            }
            if (project.hasProperty("AKANE_RELEASE_KEY_ALIAS")) {
                signingConfig = signingConfigs["release"]
            }
        }
        debug {
            applicationIdSuffix = ".debug"
            if (project.hasProperty("AKANE_RELEASE_KEY_ALIAS")) {
                signingConfig = signingConfigs["release"]
            }
        }
    }

    // https://gitlab.com/IzzyOnDroid/repo/-/issues/491
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    lint {
        checkReleaseBuilds = false
    }
}

// https://stackoverflow.com/a/77745844
tasks.withType<PackageAndroidArtifact> {
    doFirst { appMetadata.asFile.orNull?.writeText("") }
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(17)
    }
}

kotlin {
    jvmToolchain(17)
    compilerOptions {
        freeCompilerArgs = listOf(
            "-Xno-param-assertions",
            "-Xno-call-assertions",
            "-Xno-receiver-assertions"
        )
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

configurations.configureEach {
    exclude("org.jetbrains.kotlin", "kotlin-stdlib-jdk7")
    exclude("org.jetbrains.kotlin", "kotlin-stdlib-jdk8")
    exclude("androidx.recyclerview", "recyclerview")
}

dependencies {
    val media3Version = "1.6.0-rc01"
    val roomVersion = "2.7.0-rc02"
    val jellyfinSdkVersion = "1.8.12"
    val slf4jVersion = "2.0.18"

    ksp("androidx.room:room-compiler:$roomVersion")
    implementation("androidx.room:room-runtime:$roomVersion")
    implementation("androidx.room:room-ktx:$roomVersion")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-ktx:1.10.1")
    implementation("androidx.concurrent:concurrent-futures-ktx:1.2.0")
    implementation("androidx.transition:transition-ktx:1.5.1") // <-- for predictive back
    implementation("androidx.fragment:fragment-ktx:1.8.6")
    implementation("androidx.core:core-splashscreen:1.2.0-beta01")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.9.0-alpha12")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.constraintlayout:constraintlayout:2.2.1")
    implementation("androidx.media3:media3-exoplayer:$media3Version")
    implementation("androidx.media3:media3-exoplayer-midi:$media3Version")
    implementation("androidx.media3:media3-session:$media3Version")
    implementation("androidx.preference:preference-ktx:1.2.1")
    implementation("com.google.android.material:material:1.13.0-alpha11")
    implementation("com.google.android.flexbox:flexbox:3.0.0")
    implementation("me.zhanghai.android.fastscroll:library:1.3.0")
    implementation("io.coil-kt.coil3:coil:3.1.0")
    implementation("io.coil-kt.coil3:coil-network-okhttp:3.1.0")
    // Jellyfin: official Kotlin SDK (LGPL-3.0), shares one OkHttp client with media3
    implementation("org.jellyfin.sdk:jellyfin-core:$jellyfinSdkVersion")
    implementation("androidx.media3:media3-datasource-okhttp:$media3Version")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    // The SDK logs through kotlin-logging, which needs an slf4j binding on the classpath or every
    // API call dies with NoClassDefFoundError. Visible while developing, silent in release.
    debugImplementation("org.slf4j:slf4j-simple:$slf4jVersion")
    releaseImplementation("org.slf4j:slf4j-nop:$slf4jVersion")
    implementation(files("../libs/lib-decoder-ffmpeg-release.aar"))
    implementation(projects.recyclerview)
    // Apple-style widget library the upstream Accord UI is built on.
    implementation(projects.cupertino)
    implementation(libs.hiddenapibypass)
    // Spring physics for the iOS-style rubber-band overscroll.
    implementation("androidx.dynamicanimation:dynamicanimation:1.0.0")
    implementation("androidx.core:core-ktx:1.13.1")
    // --- below does not apply to release builds ---
    debugImplementation("com.squareup.leakcanary:leakcanary-android:3.0-alpha-8")
    testImplementation("junit:junit:4.13.2")
}

fun String.runCommand(
    workingDir: File = File(".")
): String = providers.exec {
    setWorkingDir(workingDir)
    commandLine(split(' '))
}.standardOutput.asText.get().removeSuffixIfPresent("\n")

fun readProperties(propertiesFile: File) = Properties().apply {
    propertiesFile.inputStream().use { fis ->
        load(fis)
    }
}
