import org.jetbrains.kotlin.gradle.dsl.JvmTarget
plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.linuxdroid.native_bridge"
    compileSdk = libs.versions.compileSdk.get().toInt()
    ndkVersion = libs.versions.ndk.get()
    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
        // Frozen architecture: Android arm64-v8a only. libweston is cross-built
        // exclusively for arm64-v8a / API 36+ (native/weston), so x86_64 is not
        // produced or linked.
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
        externalNativeBuild {
            cmake {
                cppFlags("-std=c++17", "-Wall", "-Wextra", "-fexceptions", "-frtti")
                arguments("-DANDROID_STL=c++_shared")
                // CI passes -PreqWeston to require the real libweston path; the
                // native CMake then fails if LINUXDROID_HAS_LIBWESTON is unset,
                // so a fallback-only Phase 3 build cannot pass.
                if (project.hasProperty("reqWeston")) {
                    arguments("-DLINUXDROID_REQUIRE_LIBWESTON=ON")
                }
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
}
kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":core:core-model"))
    implementation(libs.kotlinx.coroutines.android)
    testImplementation(libs.junit)
    testImplementation(libs.truth)
}
