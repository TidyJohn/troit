import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.compose")
}

kotlin {
    sourceSets.named("main") { kotlin.srcDir("../shared/kotlin") }
}

sourceSets.named("main") { resources.srcDir("../shared/resources") }

dependencies {
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.10.2")
    // When playback breaks, bump this first (same version as the Android app).
    implementation("com.github.teamnewpipe:NewPipeExtractor:v0.27.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("io.coil-kt.coil3:coil-compose:3.6.3")
    implementation("io.coil-kt.coil3:coil-network-okhttp:3.6.3")
    implementation("org.json:json:20260814")
    implementation("net.java.dev.jna:jna:5.19.1") // Match native Windows chrome to the Compose theme.
    testImplementation("junit:junit:4.13.2")
}

compose.desktop {
    application {
        mainClass = "app.tunes.MainKt"
        nativeDistributions {
            // One release: `:desktop:packageExe` writes build/compose/binaries/main/exe/Troit-<version>.exe.
            targetFormats(TargetFormat.Exe)
            packageName = "Troit"
            packageVersion = "1.14.0"
            windows {
                iconFile.set(layout.projectDirectory.file("resources/windows/troit.ico"))
                perUserInstall = true // %LOCALAPPDATA%\Troit, no administrator prompt
                menu = true
                shortcut = true
                upgradeUuid = "6cde4bbe-e5a7-43ee-8912-fe76c23ccb2e" // Never change: lets a new installer replace the old version.
            }
            includeAllModules = true
            appResourcesRootDir.set(layout.projectDirectory.dir("resources")) // mpv.exe goes in resources/windows
        }
    }
}
