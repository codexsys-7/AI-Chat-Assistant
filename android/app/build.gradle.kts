import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

val aiProviderMode = configuredProperty("ai.provider", "MOCK")
val aiBackendUrl = configuredProperty("ai.backend.url", "http://10.0.2.2:8080")

android {
    namespace = "dev.probe.textselection"
    compileSdk = 37

    defaultConfig {
        applicationId = "dev.probe.textselection"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"
        buildConfigField("String", "AI_PROVIDER", aiProviderMode.toBuildConfigString())
        buildConfigField("String", "AI_BACKEND_URL", aiBackendUrl.toBuildConfigString())
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.09.00")
    implementation(composeBom)

    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")

    testImplementation("junit:junit:4.13.2")
}

private fun configuredProperty(name: String, default: String): String {
    val fromProject = (findProperty(name) as String?)?.trim()?.takeIf { it.isNotEmpty() }
    if (fromProject != null) return fromProject
    val file = rootProject.file("local.properties")
    if (!file.isFile) return default
    val properties = Properties()
    file.inputStream().use { properties.load(it) }
    return properties.getProperty(name)?.trim()?.takeIf { it.isNotEmpty() } ?: default
}

private fun String.toBuildConfigString(): String = buildString {
    append('"')
    for (ch in this@toBuildConfigString) {
        when (ch) {
            '\\' -> append("\\\\")
            '"' -> append("\\\"")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            else -> append(ch)
        }
    }
    append('"')
}
