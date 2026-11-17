plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "ru.marshrut.razvozki"
    compileSdk = 35

    defaultConfig {
        applicationId = "ru.marshrut.razvozki"
        minSdk = 26
        targetSdk = 35
        versionCode = 3
        versionName = "1.2"
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
    // Библиотека распознавания текста Tesseract 5
    implementation("cz.adaptech.tesseract4android:tesseract4android:4.9.0")

    // Поддержка FileProvider для открытия фотографий с камеры
    implementation("androidx.core:core:1.15.0")
}
