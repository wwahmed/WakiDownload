import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Release signing. The keystore lives OUTSIDE the repo and is load-bearing: the in-app
// updater pins this certificate's SHA-256 (RELEASE_CERT_SHA256 below) and publish.sh
// refuses to ship an APK signed by anything else. Regenerating the keystore would make
// Android refuse every future update; the phone would need an uninstall and reinstall.
val keystoreDir = File(System.getProperty("user.home"), ".wakilabs-keystores")
val keystoreFile = File(keystoreDir, "WakiDownload.jks")
val keystorePasswordFile = File(keystoreDir, "WakiDownload.password")
val releaseCertSha256 = "b155b93522ccf54d35e3a0b5a268a36e766413f679e44e0dd34e68b33ff532c8"

// Cloud sign-in configuration, outside the repo. Google needs nothing here (package + cert are
// registered in the waki-brain Cloud project). Microsoft needs the Entra app (client) id; the
// redirect URI must match the one registered there. See docs/CLOUD_SETUP.md.
val oauthProps = Properties().apply {
    val f = File(keystoreDir, "WakiDownload.oauth.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
val msClientId: String = oauthProps.getProperty("microsoft.clientId", "").trim()
val msRedirectUri: String = oauthProps.getProperty("microsoft.redirectUri", "wakidownload://oauth/microsoft").trim()

android {
    namespace = "dev.wakilabs.wakidownload"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.wakilabs.wakidownload"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"

        buildConfigField("String", "RELEASE_CERT_SHA256", "\"$releaseCertSha256\"")
        buildConfigField("String", "REPO_URL", "\"https://github.com/wwahmed/WakiDownload\"")
        buildConfigField("String", "MS_CLIENT_ID", "\"$msClientId\"")
        buildConfigField("String", "MS_REDIRECT_URI", "\"$msRedirectUri\"")
        buildConfigField(
            "String",
            "UPDATE_MANIFEST_URL",
            "\"https://github.com/wwahmed/WakiDownload/releases/latest/download/latest.json\"",
        )
    }

    signingConfigs {
        create("release") {
            if (keystoreFile.exists() && keystorePasswordFile.exists()) {
                val pw = keystorePasswordFile.readText().trim()
                storeFile = keystoreFile
                storePassword = pw
                keyAlias = "wakidownload"
                keyPassword = pw
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    buildFeatures {
        buildConfig = true
        viewBinding = true
    }

    testOptions { unitTests.isReturnDefaultValues = true }
}

// A release build without the real keystore would silently fall back to an unsigned or
// debug-signed APK, which installs once and then breaks every update. Fail loudly instead.
gradle.taskGraph.whenReady {
    if (hasTask(":app:assembleRelease") || hasTask(":app:packageRelease")) {
        check(keystoreFile.exists() && keystorePasswordFile.exists()) {
            "Release keystore missing: expected $keystoreFile and $keystorePasswordFile"
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.13.0")
    implementation("androidx.recyclerview:recyclerview:1.3.1")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.work:work-runtime-ktx:2.10.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("com.google.android.gms:play-services-auth:21.1.1")
    implementation("androidx.browser:browser:1.8.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20180813")
}
