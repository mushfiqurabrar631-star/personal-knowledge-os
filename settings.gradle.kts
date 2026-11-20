pluginManagement {
    repositories {
        google()
        // Maven Central via its canonical host (repo.maven.apache.org is not reachable in every environment)
        maven("https://repo1.maven.org/maven2")
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        // Maven Central via its canonical host (repo.maven.apache.org is not reachable in every environment)
        maven("https://repo1.maven.org/maven2")
    }
}
rootProject.name = "personal-knowledge-os"
include(":app")
include(":core:data")
include(":core:notion")
