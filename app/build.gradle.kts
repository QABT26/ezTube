import java.io.ByteArrayOutputStream

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.kapt")
}

fun gitBuildId(): String {
    val output = ByteArrayOutputStream()
    return runCatching {
        exec {
            commandLine("git", "rev-parse", "--short=7", "HEAD")
            standardOutput = output
            isIgnoreExitValue = true
        }
        output.toString().trim().ifBlank { "unknown" }
    }.getOrDefault("unknown")
}

val gitBuildId = gitBuildId()

android {
    namespace = "com.qabt.eztube"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.qabt.eztube"
        minSdk = 23
        targetSdk = 35
        versionCode = 4
        versionName = "0.2.0-beta.3"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    buildTypes {
        debug {
            buildConfigField("String", "BUILD_ID", "\"$gitBuildId\"")
        }
        release {
            buildConfigField("String", "BUILD_ID", "\"$gitBuildId\"")
        }
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.4")

    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.activity:activity-compose:1.10.0")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("io.coil-kt:coil-compose:2.7.0")

    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    kapt("androidx.room:room-compiler:2.6.1")

    implementation("androidx.media3:media3-exoplayer:1.6.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.6.1")
    implementation("androidx.media3:media3-exoplayer-dash:1.6.1")
    implementation("androidx.media3:media3-session:1.6.1")
    implementation("androidx.media3:media3-ui:1.6.1")
    implementation("com.github.InfinityLoop1308.PipePipeExtractor:extractor:c68e10e2e97495877832d8df6cbac55478083019")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    debugImplementation("androidx.compose.ui:ui-tooling")
    testImplementation("junit:junit:4.13.2")
}
