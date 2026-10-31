plugins {
    id("com.android.application")
}
android {
    namespace = "pt.remotephotobridge"
    compileSdk = 35
    defaultConfig {
        applicationId = "pt.remotephotobridge"
        minSdk = 26
        targetSdk = 35
        versionCode = 2
        versionName = "1.0.0-alpha2"
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
