pluginManagement {
    repositories { google(); maven("https://maven-central.storage-download.googleapis.com/maven2/"); gradlePluginPortal() }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        maven("https://maven-central.storage-download.googleapis.com/maven2/")
        // Pleos Playground 공식 Maven 저장소 (Vehicle SDK, FusedLocation SDK)
        maven("https://nexus-playground.pleos.ai/repository/maven-releases/")
    }
}
rootProject.name = "KEEPER"
include(":core", ":car", ":phone")
