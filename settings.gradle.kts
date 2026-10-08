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
        maven(url = uri("$rootDir/local-maven"))
        google()
        mavenCentral()
        maven(url = "https://jitpack.io")
    }
}
rootProject.name = "ezTube"
include(":app")
