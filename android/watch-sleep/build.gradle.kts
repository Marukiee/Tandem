import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    testImplementation(kotlin("test"))
}

tasks.test { useJUnitPlatform() }

// Re-analyses logs copied from the watch: ./gradlew :watch-sleep:analyze --args="night.tws"
tasks.register<JavaExec>("analyze") {
    group = "application"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("nl.markmaaktmedia.tandem.watch.sleep.AnalyzeCliKt")
}
