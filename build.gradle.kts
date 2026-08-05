plugins {
    kotlin("jvm") version "2.4.10"
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.10"
    id("org.jetbrains.compose") version "1.11.0"
    application
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(compose.desktop.currentOs)
    implementation(files("/Users/rock3r/src/jbr-api/out/jbr-api-SNAPSHOT.jar"))
}

application {
    mainClass.set("MainKt")
}
