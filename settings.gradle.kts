pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "LinuxDroid"

// Application
include(":app")

// Core modules
include(":core:core-model")
include(":core:core-logging")
include(":core:core-database")
include(":core:core-runtime")
include(":core:core-process")
include(":core:core-session")
include(":core:core-filesystem")
include(":core:core-storage")
include(":core:core-display")
include(":core:core-gpu")
include(":core:core-input")
include(":core:core-gui")
include(":core:core-audio")
include(":core:core-network")
include(":core:core-package")
include(":core:core-diagnostics")
include(":core:core-host")

// Linux modules
include(":linux:bootstrap")

// Native modules
include(":native:bridge")
