plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.tencent.mm"
    compileSdk {
        version = release(37)
    }
    defaultConfig {
        applicationId = "com.tencent.mm"
        minSdk = 30
        targetSdk = 37
        versionCode = 1
        versionName = "mock-1"
    }
    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
