import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val localProps = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}

android {
    namespace = "com.happycrew.happytrunk"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.happycrew.happytrunk"
        minSdk = 28
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        // 공공데이터포털 키. local.properties 에 DATA_GO_KR_KEY=... 없으면 시드 데이터로 동작
        buildConfigField("String", "DATA_GO_KR_KEY", "\"${localProps.getProperty("DATA_GO_KR_KEY", "")}\"")
    }
    // 서명키는 저장소 밖. local.properties 의 RELEASE_* 없으면 release 는 미서명
    signingConfigs {
        localProps.getProperty("RELEASE_STORE_FILE")?.let { f ->
            create("release") {
                storeFile = file(f)
                storePassword = localProps.getProperty("RELEASE_STORE_PASSWORD")
                keyAlias = localProps.getProperty("RELEASE_KEY_ALIAS")
                keyPassword = storePassword
            }
        }
    }
    buildTypes { release { isMinifyEnabled = false; signingConfig = signingConfigs.findByName("release") } }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true; buildConfig = true }
}

dependencies {
    implementation("ai.pleos.playground:Vehicle:2.3.2")
    implementation("ai.pleos.playground:NaviHelper:3.0.5")
    implementation("ai.pleos.playground:TextToSpeech:3.3.1")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.0")
    implementation(platform("androidx.compose:compose-bom:2024.04.01"))
    implementation("androidx.compose.material3:material3")
    testImplementation("junit:junit:4.13.2")
}
