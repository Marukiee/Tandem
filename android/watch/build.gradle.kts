import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// The release key of the phone app, from the same git-ignored file: a watch only accepts an update signed like what it has.
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) keystorePropertiesFile.inputStream().use { load(it) }
}
val hasReleaseKeystore = keystoreProperties.getProperty("storeFile") != null

// CI stamps the version from the tag, like the phone app: -PversionName=1.2.3 -PversionCode=10203
val appVersionName = (findProperty("versionName") as String?) ?: "0.1.0"
val appVersionCode = ((findProperty("versionCode") as String?) ?: "1").toInt()

android {
    namespace = "nl.markmaaktmedia.tandem.watch"
    compileSdk = 36

    defaultConfig {
        applicationId = "nl.markmaaktmedia.tandem.watch"
        // Wear OS 4: the first with the Bluetooth calls this app uses.
        minSdk = 33
        targetSdk = 36
        versionCode = appVersionCode
        versionName = appVersionName
    }

    signingConfigs {
        if (hasReleaseKeystore) {
            create("release") {
                storeFile = rootProject.file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildTypes {
        release {
            resValue("bool", "test_tools", "false")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            signingConfig = if (hasReleaseKeystore) signingConfigs.getByName("release") else signingConfigs.getByName("debug")
        }
        // For a test watch without the release key: a debuggable build runs Compose several times slower, and scrolling shows it.
        // It does not install over a release build, because the key differs.
        create("fast") {
            initWith(getByName("release"))
            signingConfig = signingConfigs.getByName("debug")
            resValue("bool", "test_tools", "true")
        }
        debug { resValue("bool", "test_tools", "true") }
    }

    buildFeatures { compose = true }
}

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

dependencies {
    implementation(project(":watch-protocol"))
    implementation(project(":watch-sleep"))
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.animation)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.wear.compose.material3)
    implementation(libs.wear.compose.foundation)
}
