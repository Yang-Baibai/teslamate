plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "com.teslamate.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.teslamate.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 49
        versionName = "0.31.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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
        buildConfig = true
    }
    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

dependencies {
    // 阿里云镜像对 compose-bom 的 POM 代理不完整（tooling-preview 约束丢失），
    // 因此不用 BOM，直接锁定版本（对应官方 BOM 2024.12.01 的版本组合）。
    val composeUi = "1.7.6"
    implementation("androidx.compose.ui:ui:$composeUi")
    implementation("androidx.compose.ui:ui-tooling-preview:$composeUi")
    implementation("androidx.compose.material3:material3:1.3.1")
    implementation("androidx.compose.material:material-icons-extended:$composeUi")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    // 毛玻璃（真实 RenderEffect 模糊，API 31+；以下自动退化为半透明）
    implementation("dev.chrisbanes.haze:haze-android:1.0.0")
    // 不再引入 WorkManager：后台推送与提醒改由本机的 Hermes 负责
    // （项目根 scripts/watch-car.ps1 + Windows 计划任务），App 只做查看。
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("io.coil-kt:coil-compose:2.7.0")
    debugImplementation("androidx.compose.ui:ui-tooling:$composeUi")
}
