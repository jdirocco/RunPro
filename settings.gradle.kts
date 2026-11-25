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

rootProject.name = "RunPro"

include(
    ":core:model",
    ":core:recording",
    ":core:journal",
    ":core:fit",
    ":core:dynamics",
    ":core:ble",
    ":tools:replay",
    ":wear",
    ":mobile"
)
