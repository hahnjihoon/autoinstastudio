import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

val signingFile = rootProject.file(".signing/release.properties")
val signingValues = Properties().apply { if (signingFile.isFile) signingFile.inputStream().use { load(it) } }
val defaultsFile = rootProject.file(".local/credential-defaults.properties")
val defaults = Properties().apply { if (defaultsFile.isFile) defaultsFile.reader(Charsets.UTF_8).use { load(it) } }

android {
    namespace = "com.autoinstastudio.app"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.autoinstastudio.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"
        listOf("DEFAULT_OPENAI_API_KEY", "DEFAULT_INSTAGRAM_USERNAME").forEach { name ->
            val value = defaults.getProperty(name, "").replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r")
            buildConfigField("String", name, "\"$value\"")
        }
    }
    buildFeatures { compose = true; buildConfig = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    signingConfigs {
        create("release") {
            if (signingFile.isFile) {
                storeFile = rootProject.file(signingValues.getProperty("storeFile"))
                storePassword = signingValues.getProperty("storePassword")
                keyAlias = signingValues.getProperty("keyAlias")
                keyPassword = signingValues.getProperty("keyPassword")
                storeType = "PKCS12"
            }
        }
    }
    buildTypes { release { isMinifyEnabled = false; signingConfig = signingConfigs.getByName("release") } }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2026.02.01"))
    implementation("androidx.activity:activity-compose:1.8.2")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.material3:material3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("androidx.work:work-runtime-ktx:2.10.1")
}
