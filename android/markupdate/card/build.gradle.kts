// De Compose-kaart van markupdate, bovenop de kern (../core). Dit bestand staat in elke app hetzelfde; wijzig het alleen in Keeper
// (zie ../README.md). Zelfstandig: geen `libs.`-verwijzingen, geen `repositories {}`, plugins zonder versie.
//
// De Compose-compiler komt uit de Kotlin-versie van de app (plugin org.jetbrains.kotlin.plugin.compose); de BOM hier is de ondergrens, een
// nieuwere uit de app wint.
plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "nl.markmaaktmedia.markupdate.ui"
    compileSdk = 36
    // Elke resource van de kaart begint met markupdate_, zodat niets uit een app ermee botst.
    resourcePrefix = "markupdate_"

    defaultConfig {
        minSdk = 24
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    lint {
        abortOnError = false
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        freeCompilerArgs.addAll(
            "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api",
            "-opt-in=androidx.compose.foundation.ExperimentalFoundationApi",
            "-opt-in=androidx.compose.animation.ExperimentalAnimationApi",
        )
    }
}

dependencies {
    // De app gebruikt de kern (MarkUpdate, MarkUpdater, UpdateState) rechtstreeks.
    api(project(":markupdate:core"))

    implementation(platform("androidx.compose:compose-bom:2025.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.animation:animation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
    // Compose-ui vraagt zelf activity-ktx 1.8.2; de apps gebruiken al een nieuwere. Hier vastgezet zodat de module ook los te resolven is
    // (lint en AAR-controle doen dat) zonder een verouderde versie te hoeven ophalen.
    implementation("androidx.activity:activity-ktx:1.11.0")
}
