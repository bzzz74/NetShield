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
        // Xposed classic API (de.robv.android.xposed:api) is hosted here:
        maven("https://repository-cdn.liferay.com/nexus/content/repositories/gradle-plugins/")
        maven("https://artifactory.appodeal.com/appodeal-public/")
    }
}

rootProject.name = "NetShield"
include(":app")
