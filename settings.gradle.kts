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

rootProject.name = "CardboardHands"
include(":app")
include(":sdk")
include(":orangehanding")
// VINS-Mono's native core: 6DoF for phones ARCore does not run on (see vins/UPSTREAM.txt).
include(":vins")
