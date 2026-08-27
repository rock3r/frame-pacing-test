plugins {
    kotlin("jvm") version "2.4.10"
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.10"
    // 1.12.0-beta03 pulls skiko 0.150.1, matching compose-multiplatform-core jb-main,
    // so locally built CMP jars can be prepended without skiko ABI skew.
    id("org.jetbrains.compose") version "1.12.0-beta03"
    application
}

kotlin {
    jvmToolchain(21)
}

// Compose pulls org.jetbrains.runtime:jbr-api:1.9.0 transitively. That predates
// FramePacing entirely, and installDist puts both it and the local SNAPSHOT into lib/ --
// where a `lib/*` classpath sorts jbr-api-1.9.0.jar ahead of jbr-api-SNAPSHOT.jar,
// because a digit sorts before a letter. The old jar then shadows the new one,
// JBR.isFramePacingSupported() throws NoSuchMethodError, and skiko falls back to
// unpaced without reporting anything: paced and unpaced measure identical, which reads
// as "pacing does nothing on this platform" rather than as a packaging fault.
configurations.all {
    exclude(group = "org.jetbrains.runtime", module = "jbr-api")
}

dependencies {
    implementation(compose.desktop.currentOs)
    implementation(files("libs/jbr-api-SNAPSHOT.jar"))
}

application {
    mainClass.set("MainKt")
}

// Local CMP override, mirroring the jbr-skia-zero-copy magic-jewel consumption pattern:
// point localCmpOut / LOCAL_CMP_OUT at a compose-multiplatform-core output root (the
// checkout's out/compose-multiplatform-core) after building the changed modules there
// (e.g. ./gradlew :compose:ui:ui:desktopJar); the jars are prepended ahead of the
// released CMP artifacts on the runtime classpath.
val localCmpOut = providers.gradleProperty("localCmpOut")
    .orElse(providers.environmentVariable("LOCAL_CMP_OUT"))

fun localCmpJars(): FileCollection {
    val root = localCmpOut.orNull?.let(::file) ?: return files()
    if (!root.isDirectory) return files()
    return files(
        fileTree(root) {
            include("compose/**/build/libs/*-desktop-9999.0.0-SNAPSHOT.jar")
        }
    )
}

// compose-multiplatform-core jb-main builds against this skiko (gradle/libs-fork.versions.toml);
// the released 1.12.0-beta03 artifacts pull 0.150.1, whose ABI the local jars don't match.
val localCmpSkikoVersion = providers.gradleProperty("localCmpSkikoVersion")
    .getOrElse("0.151.0-alpha05")

if (localCmpOut.isPresent) {
    configurations.all {
        resolutionStrategy.eachDependency {
            if (requested.group == "org.jetbrains.skiko") {
                useVersion(localCmpSkikoVersion)
                because("locally built CMP jars require this skiko ABI")
            }
        }
    }
}

val spikeJbrHome = providers.gradleProperty("spikeJbrHome")
    .orElse(providers.environmentVariable("SPIKE_JBR_HOME"))
    .orElse(
        "/Users/rock3r/src/JetBrainsRuntime-frame-pacing-spike/" +
            "build/macosx-aarch64-server-release/images/jdk"
    )

tasks.register<JavaExec>("runLocalCmp") {
    group = ApplicationPlugin.APPLICATION_GROUP
    description = "Run on the spike JBR with locally built CMP jars prepended to the classpath."
    val patched = localCmpJars()
    classpath = patched + sourceSets.main.get().runtimeClasspath
    mainClass.set("MainKt")
    setExecutable("${spikeJbrHome.get()}/bin/java")
    systemProperty("pacing", providers.gradleProperty("pacing").getOrElse("off"))
    systemProperty(
        "compose.swing.frame.pacing",
        providers.gradleProperty("cmpFramePacing").getOrElse("false")
    )
    doFirst {
        logger.lifecycle("Prepending ${patched.files.size} local CMP jar(s): ${patched.files.map { it.name }}")
        logger.lifecycle("Runtime: $executable")
    }
}

// Skiko-direct mode (SkikoDirectMain.kt): drives a SkiaSwingLayer without Compose, against the
// pacing-capable skiko built from ~/src/skiko-frame-pacing and published to mavenLocal as
// 0.0.0-SNAPSHOT (`./gradlew :skiko:publishToMavenLocal` there). Same prepending pattern as
// runLocalCmp: the snapshot skiko jars shadow the released skiko that compose pulls in. The
// module still compiles against the released skiko, so SkikoDirectMain calls needRender()
// reflectively.
fun mavenLocalSkikoJars(): FileCollection {
    val skikoM2 = file("${System.getProperty("user.home")}/.m2/repository/org/jetbrains/skiko")
    return files(
        "$skikoM2/skiko-awt/0.0.0-SNAPSHOT/skiko-awt-0.0.0-SNAPSHOT.jar",
        "$skikoM2/skiko-awt-runtime-macos-arm64/0.0.0-SNAPSHOT/" +
            "skiko-awt-runtime-macos-arm64-0.0.0-SNAPSHOT.jar",
    )
}

tasks.register<JavaExec>("runSkikoDirect") {
    group = ApplicationPlugin.APPLICATION_GROUP
    description = "Run the SkiaSwingLayer scene on the spike JBR against mavenLocal skiko " +
        "0.0.0-SNAPSHOT. Toggle pacing with -Ppacing=true|false."
    val prepended = mavenLocalSkikoJars()
    classpath = prepended + sourceSets.main.get().runtimeClasspath
    mainClass.set("SkikoDirectMainKt")
    setExecutable("${spikeJbrHome.get()}/bin/java")
    systemProperty(
        "skiko.swing.frame.pacing",
        providers.gradleProperty("pacing").getOrElse("false")
    )
    doFirst {
        prepended.files.forEach {
            require(it.isFile) {
                "$it not found — publish skiko first: " +
                    "cd ~/src/skiko-frame-pacing && ./gradlew :skiko:publishToMavenLocal"
            }
        }
        logger.lifecycle("Prepending ${prepended.files.map { f -> f.name }}")
        logger.lifecycle("Runtime: $executable")
    }
}
