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

rootProject.name = "Loki"
include(":app")
include(":core:assistant")
include(":core:conversation")
include(":core:voice:stt")
include(":core:voice:tts")
include(":core:llm")
include(":core:models")
include(":core:tools")
include(":core:tools:local")
include(":core:theme")
include(":core:sound")
include(":core:ui")
