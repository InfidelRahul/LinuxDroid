import org.jetbrains.kotlin.gradle.dsl.JvmTarget
plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.linuxdroid.core.session"
    compileSdk = libs.versions.compileSdk.get().toInt()
    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":core:core-model"))
    implementation(project(":core:core-logging"))
    implementation(project(":core:core-runtime"))
    implementation(project(":core:core-process"))
    implementation(project(":core:core-display"))
    implementation(project(":core:core-gpu"))
    implementation(project(":core:core-input"))
    implementation(project(":core:core-audio"))
    implementation(project(":core:core-network"))
    implementation(project(":core:core-filesystem"))
    implementation(project(":core:core-storage"))
    implementation(libs.kotlinx.coroutines.android)
    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.mockk)
    testImplementation(libs.turbine)
    testImplementation(libs.truth)
    testImplementation(libs.kotlinx.coroutines.test)
}
