plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "com.madboly.waqfa"
    compileSdk = 34
    defaultConfig {
        applicationId = "com.madboly.waqfa"
        minSdk = 26
        targetSdk = 34
        versionCode = 16
        versionName = "0.3.1"
    }
    signingConfigs {
        create("release") {
            storeFile = file(System.getenv("WAQFA_KS") ?: "none.jks")
            storePassword = System.getenv("WAQFA_KS_PASS")
            keyAlias = "waqfa"
            keyPassword = System.getenv("WAQFA_KS_PASS")
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            signingConfig = signingConfigs.getByName("release")
        }
    }
    androidResources { noCompress += "webp" }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}
dependencies { }
