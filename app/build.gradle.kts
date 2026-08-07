import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.ksp)
}

val osMemoryLocalProperties = Properties().apply {
    val propertiesFile = rootProject.file("local.properties")
    if (propertiesFile.isFile) propertiesFile.inputStream().use(::load)
}
val phase3ApiKey = providers.environmentVariable("OS_MEMORY_API_KEY").orNull
    ?: osMemoryLocalProperties.getProperty("osmemory.apiKey", "")
val escapedPhase3ApiKey = phase3ApiKey.trim()
    .replace("\r", "")
    .replace("\n", "")
    .replace("\\", "\\\\")
    .replace("\"", "\\\"")

android {
    namespace = "com.example.osmemory"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.example.osmemory"
        minSdk = 24
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // 密钥只从本机 local.properties / 环境变量注入，不进入 Git 历史。
        buildConfigField("String", "PHASE3_API_KEY", "\"$escapedPhase3ApiKey\"")
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        buildConfig = true
    }
    packaging {
        jniLibs.useLegacyPackaging = true
    }
}

dependencies {
    implementation(project(":llama-runtime"))
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.core.ktx)
    implementation(libs.material)
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
    implementation(libs.okhttp)
    testImplementation(libs.junit)
    testImplementation(libs.org.json) // JVM 单测中提供 org.json（Android 上使用平台内置实现）
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
}
