import java.util.Properties

plugins {
    id("com.android.application")
    kotlin("android")
    kotlin("plugin.compose")
}

// release 서명 정보는 git 에 올리지 않는 local.properties 에서 읽는다(키 이름은 README 참고).
val KEY_STORE_FILE = "release.storeFile"
val KEY_STORE_PASSWORD = "release.storePassword"
val KEY_ALIAS = "release.keyAlias"
val KEY_PASSWORD = "release.keyPassword"

val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
val releaseStoreFile: String? = localProps.getProperty(KEY_STORE_FILE)

android {
    namespace = "com.yeopjari.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.yeopjari.chingu"
        // Pleos 문서: 제품 앱은 API 28 이상(서명 키 로테이션 지원)
        minSdk = 28
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    signingConfigs {
        val missing = listOf(KEY_STORE_PASSWORD, KEY_ALIAS, KEY_PASSWORD).filter { localProps.getProperty(it) == null }
        if (releaseStoreFile != null && missing.isNotEmpty()) {
            throw GradleException("local.properties 에 release 서명 값이 빠졌습니다: $missing")
        }
        if (releaseStoreFile != null) {
            create("release") {
                storeFile = file(releaseStoreFile)
                storePassword = localProps.getProperty(KEY_STORE_PASSWORD)
                keyAlias = localProps.getProperty(KEY_ALIAS)
                keyPassword = localProps.getProperty(KEY_PASSWORD)
            }
        } else {
            logger.warn("local.properties 에 $KEY_STORE_FILE 이 없어 release APK 를 서명하지 않습니다.")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }

    // 차속·기어를 읽기 위한 AAOS Car API
    useLibrary("android.car")
}

dependencies {
    implementation(project(":core"))

    implementation("ai.pleos.playground:Vehicle:2.3.2")
    implementation("ai.pleos.playground:NaviHelper:3.0.5")
    implementation("ai.pleos.playground:TextToSpeech:3.3.1")

    implementation(platform("androidx.compose:compose-bom:2024.09.03"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}
