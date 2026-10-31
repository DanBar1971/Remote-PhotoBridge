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
        versionCode = 3
        versionName = "1.0.0-alpha3"
    }
    // This is a development-only signature. Production releases must use
    // a secure private signing key, not the public ALPHA test keystore.
    signingConfigs {
        create("alphaTest") {
            storeFile = file("signing/alpha-test-signing.p12")
            storeType = "PKCS12"
            storePassword = "PhotoBridgeAlphaTestOnly2026"
            keyAlias = "photobridge-alpha"
            keyPassword = "PhotoBridgeAlphaTestOnly2026"
        }
    }
    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("alphaTest")
        }
        release {
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
