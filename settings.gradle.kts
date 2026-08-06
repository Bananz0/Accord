@file:Suppress("UnstableApiUsage")

enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.9.0"
}

rootProject.name = "Accord"
include(":app", ":recyclerview")

// The Cupertino widget library the upstream Accord UI is built on, vendored as a submodule. Its
// repository root is a full sample project, so point Gradle at the library module inside it rather
// than including the whole thing.
include(":Cupertino")
project(":Cupertino").projectDir = file("Cupertino/Cupertino")

// Carried over from upstream Accord, whose UI these modules back:
//   libPhonograph - the library model (Album/Artist/Playlist, the MediaStore readers) the Accord
//                   screens are written against. Vendored as a submodule the same way Cupertino is,
//                   with a thin wrapper module pointing Gradle at the library inside it.
//   hificore      - hidden-API AudioTrack access behind the audio format readout, plus reflective
//                   audio effects. Builds a small native library; the USB-audio half upstream
//                   leaves commented out is not built.
//   misc:*        - the audio effect forwarding stubs hificore links against.
include(":libPhonograph", ":hificore", ":misc:audiofxfwd", ":misc:audiofxstub")

// misc:alacdecoder is present in the tree but deliberately not built yet: it calls Util.putInt24,
// which media3 only grew in 1.9.0, and this app is still on 1.6.0-rc01 because the prebuilt
// lib-decoder-ffmpeg AAR in libs/ is compiled against that. Include it in the same change that
// raises media3 and rebuilds that AAR, alongside the Accord player it feeds.
