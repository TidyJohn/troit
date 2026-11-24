plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "app.tunes"
    compileSdk = 37
    defaultConfig {
        applicationId = "app.tunes"
        minSdk = 26
        targetSdk = 36
        versionCode = 18
        versionName = "0.18"
    }
    buildTypes.named("release") {
        // Keep local upgrades compatible with the APKs already installed on this device.
        signingConfig = signingConfigs.getByName("debug")
        isDebuggable = false
        isMinifyEnabled = false // Preserve extractor reflection until shrinker rules are validated.
    }
    buildFeatures { compose = true }
    sourceSets.getByName("main").kotlin.directories.add("../shared/kotlin")
    sourceSets.getByName("main").res.directories.add("../shared/resources")
    compileOptions {
        // NewPipeExtractor needs desugaring below API 33
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs_nio:2.1.5")
    // When playback breaks, bump this first: https://github.com/TeamNewPipe/NewPipeExtractor/releases
    implementation("com.github.teamnewpipe:NewPipeExtractor:v0.27.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("androidx.media3:media3-exoplayer:1.11.1")
    implementation("androidx.media3:media3-session:1.11.1")
    implementation("androidx.media3:media3-ui:1.11.1") // PlayerView: shows the music video picture at its own aspect ratio
    implementation("androidx.work:work-runtime-ktx:2.12.0")
    implementation(platform("androidx.compose:compose-bom:2026.09.00"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("io.coil-kt.coil3:coil-compose:3.6.3")
    implementation("io.coil-kt.coil3:coil-network-okhttp:3.6.3")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20260814") // Android's org.json is only a stub in unit tests
}
