
pluginManagement {
    repositories {
        google()
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)

    repositories {
        google()

        maven {
            url = uri("https://jitpack.io")
        }

        maven {
            url = uri("https://maven-central.storage-download.googleapis.com/maven2/")
        }

        mavenCentral()
    }
}

rootProject.name = "MarshrutRazvozki"

include(":app")
