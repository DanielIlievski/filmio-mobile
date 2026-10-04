plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.example.filmio.feature.catalog.data"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        minSdk = 29
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    // Feature domain contract
    implementation(project(":feature:catalog:domain"))
    implementation(project(":core:data"))
    implementation(project(":feature:catalog:database"))

    // Dependency injection
    implementation(platform(libs.koin.bom))
    implementation(libs.koin.core)

    // Coroutines and pagination
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.androidx.paging.runtime)

    // Retrofit networking and Moshi conversion
    implementation(libs.retrofit.core)
    implementation(libs.retrofit.converter.moshi)
    implementation(libs.okhttp.core)
    implementation(libs.moshi.kotlin)

    // JVM data tests
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.androidx.paging.testing)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.mockito.subclass)
}
