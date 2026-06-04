import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.play.publisher)
}

// Load release signing config from keystore.properties (gitignored) if present.
// Falls back to debug signing so builds work without the secrets file.
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) {
        keystorePropertiesFile.inputStream().use { load(it) }
    }
}
val hasReleaseSigning = keystoreProperties.getProperty("storeFile")?.let { file(it).exists() } == true

android {
    namespace = "app.toddbsmith.bouncybubbles"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.toddbsmith.bouncybubbles"
        minSdk = 26
        targetSdk = 35
        versionCode = 2
        versionName = "1.0.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
    }
}

play {
    // CI sets PLAY_SERVICE_ACCOUNT_JSON via secret; local builds may drop a
    // play-account.json next to this file (gitignored).
    val envPath = System.getenv("PLAY_SERVICE_ACCOUNT_JSON")
    serviceAccountCredentials.set(
        if (envPath != null) file(envPath) else file("play-account.json")
    )
    // Track is overridable via -PplayTrack=production (default: internal).
    val playTrack = project.findProperty("playTrack")?.toString() ?: "internal"
    track.set(playTrack)
    defaultToAppBundles.set(true)
    // The plugin defaults to failing if the service account JSON is absent,
    // which would break local debug builds. Skip the credential check when
    // the file isn't there.
    val credsExist = (envPath != null && file(envPath).exists()) || file("play-account.json").exists()
    if (!credsExist) {
        enabled.set(false)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    debugImplementation(libs.androidx.ui.tooling)

    testImplementation(libs.junit)
}
