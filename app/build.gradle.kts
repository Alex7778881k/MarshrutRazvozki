
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val dgisApiKey = (
    project.findProperty("dgisApiKey") as String? ?: ""
).replace("\\", "\\\\").replace("\"", "\\\"")

android {
    namespace = "ru.marshrut.razvozki"
    compileSdk = 35

    defaultConfig {
        applicationId = "ru.marshrut.razvozki"
        minSdk = 26
        targetSdk = 35
        versionCode = 5
        versionName = "1.4"

        buildConfigField(
            "String",
            "DGIS_API_KEY",
            "\"$dgisApiKey\""
        )
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("cz.adaptech.tesseract4android:tesseract4android:4.9.0")
    implementation("androidx.core:core:1.15.0")
    implementation("androidx.exifinterface:exifinterface:1.3.7")
}
