pluginManagement {
    repositories {
        google()
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        // Pleos Playground SDK (Vehicle, NaviHelper, TextToSpeech)
        maven("https://nexus-playground.pleos.ai/repository/maven-releases/")
    }
}

rootProject.name = "yeopjari-chingu"
include(":core")

// app 모듈은 Android SDK 가 있어야 빌드된다. local.properties 의 sdk.dir 또는 ANDROID_HOME 이 없으면 core 만 빌드한다.
val hasAndroidSdk = file("local.properties").takeIf { it.exists() }?.readText()?.contains("sdk.dir") == true ||
    System.getenv("ANDROID_HOME") != null || System.getenv("ANDROID_SDK_ROOT") != null
if (hasAndroidSdk) include(":app")
