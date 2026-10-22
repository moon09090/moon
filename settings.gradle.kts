pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://alphacephei.com/maven/") }
    }
}
rootProject.name = "MoonAssistant"
include(":app")
