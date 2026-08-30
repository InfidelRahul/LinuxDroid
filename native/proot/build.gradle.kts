plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.linuxdroid.native_proot"
    compileSdk = libs.versions.compileSdk.get().toInt()
    ndkVersion = libs.versions.ndk.get()
    // Pure C/C++ module (no Kotlin sources) — disable built-in Kotlin processing.
    enableKotlin = false
    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
        externalNativeBuild {
            cmake {
                cFlags("-Wall", "-Wextra", "-Wno-deprecated-declarations", "-O2")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    externalNativeBuild {
        cmake {
            path = file("CMakeLists.txt")
            version = "3.22.1"
        }
    }
}

