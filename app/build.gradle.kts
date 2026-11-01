import java.util.Properties

plugins {
    id("com.android.application")
}

// The private signing bundle is restored from a protected GitHub Actions Secret.
// No private signing keys or credentials are stored in this public repository.
val alphaSigningFile = rootProject.file("local-signing.properties")
val alphaSigning = Properties()
if (alphaSigningFile.isFile) {
    alphaSigningFile.inputStream().use { alphaSigning.load(it) }
}
android {
    namespace = "pt.remotephotobridge"
    compileSdk = 35
    defaultConfig {
        applicationId = "pt.remotephotobridge"
        minSdk = 26
        targetSdk = 35
        versionCode = 101
        versionName = "1.0.1"
    }
    signingConfigs {
        create("persistentAlpha") {
            if (alphaSigningFile.isFile) {
                storeFile = rootProject.file(alphaSigning.getProperty("storeFile"))
                storeType = "PKCS12"
                storePassword = alphaSigning.getProperty("storePassword")
                keyAlias = alphaSigning.getProperty("keyAlias")
                keyPassword = alphaSigning.getProperty("keyPassword")
            }
        }
    }
    buildTypes {
        getByName("debug") {
            // Local development without the private bundle may use the default
            // Android debug signature; CI refuses to publish such APKs.
            if (alphaSigningFile.isFile) {
                signingConfig = signingConfigs.getByName("persistentAlpha")
            }
        }
        release {
            isMinifyEnabled = false
            // Signed with the SAME protected identity as alpha8+, so users can
            // update in place. The CI refuses to publish if the key is missing.
            if (alphaSigningFile.isFile) {
                signingConfig = signingConfigs.getByName("persistentAlpha")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    // Same Nearby Connections library used in the working SaúdePrática app.
    implementation("com.google.android.gms:play-services-nearby:19.3.0")
    testImplementation("junit:junit:4.13.2")
}
