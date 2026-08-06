// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    // Kept in step with upstream Accord (AGP 8.13.2 / Kotlin 2.3.0), whose sources this app's UI is
    // ported from - its modules will not compile on the older toolchain this fork used to pin.
    val agpVersion = "8.13.2"
    id("com.android.application") version agpVersion apply false
    id("com.android.library") version agpVersion apply false
    val kotlinVersion = "2.3.0"
    kotlin("android") version kotlinVersion apply false
    kotlin("plugin.parcelize") version kotlinVersion apply false
    // KSP moved to its own version line for the Kotlin 2.3 series, so it no longer carries the
    // Kotlin version as a prefix.
    id("com.google.devtools.ksp") version "2.3.11" apply false
}

tasks.withType(JavaCompile::class) {
    options.compilerArgs.add("-Xlint:all")
}
