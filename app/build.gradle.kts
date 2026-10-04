import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

val tokenFromFile = providers.fileContents(rootProject.layout.projectDirectory.file("tmdb.properties"))
    .asText.orElse("").map { text ->
        Properties().apply { load(text.reader()) }.getProperty("TMDB_READ_ACCESS_TOKEN", "")
    }
val tmdbToken = providers.environmentVariable("TMDB_READ_ACCESS_TOKEN")
    .filter { it.isNotBlank() }
    .orElse(tokenFromFile)
    .getOrElse("")

fun String.asJavaStringLiteral(): String = buildString {
    append('"')
    for (character in this@asJavaStringLiteral) {
        append(when (character) {
            '\\' -> "\\\\"
            '"' -> "\\\""
            '\n' -> "\\n"
            '\r' -> "\\r"
            '\t' -> "\\t"
            '\b' -> "\\b"
            '\u000C' -> "\\f"
            else -> if (character.code < 32 || character.code > 126) {
                "\\u%04x".format(character.code)
            } else character.toString()
        })
    }
    append('"')
}

android {
    namespace = "com.example.filmio"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.example.filmio"
        minSdk = 29
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"
        buildConfigField("String", "TMDB_READ_ACCESS_TOKEN", tmdbToken.asJavaStringLiteral())
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    // Feature wiring
    implementation(project(":feature:catalog:data"))
    implementation(project(":feature:catalog:presentation"))
    implementation(project(":feature:catalog:database"))

    // AndroidX and Compose
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)

    // Navigation and saved routes
    implementation(libs.androidx.lifecycle.viewmodel.navigation3)
    implementation(libs.androidx.navigation3.runtime)
    implementation(libs.androidx.navigation3.ui)
    implementation(libs.kotlinx.serialization.core)

    // Dependency injection
    implementation(platform(libs.koin.bom))
    implementation(libs.koin.android)

    // Debug previews
    debugImplementation(libs.androidx.compose.ui.tooling)
}
