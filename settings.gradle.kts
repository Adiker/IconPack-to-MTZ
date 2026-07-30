pluginManagement {
    repositories {
        google()
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

rootProject.name = "IconPackToMtz"

include(
    ":app",
    ":core-model",
    ":core-archive",
    ":core-apk",
    ":core-renderer",
    ":core-mtz",
    ":core-report",
    ":core-data",
    ":feature-converter",
    ":feature-settings",
    ":feature-history",
    ":integration-shizuku",
    ":fixture-iconpack",
)
