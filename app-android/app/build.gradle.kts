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
        // ⚠️ **v0.119.0**：App 改了（续航结论改读服务端）⇒ 版本号要涨。
        //    ⚠️ **必须单调递增、不能跳号**（App 的更新判定是 remote > 本地 code
        //    ⇒ 跳过的号用户永远收不到）：120 → 121 → 122 → 123 → 124 → 125 → 126 → 127 → 128，八个是
        //    并行会话与我按序发的，**没有跳号**。
        // ⚠️ **v0.122.0**：接着 130 之后是 **131**（充电详情「实测到 80% 用了多久」改 KV 行）
        //    ⇒ 仍然**没有跳号**。
        versionCode = 131
        versionName = "0.122.0"
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
    // ⚠️ **v0.37.0：JVM 单元测试**（`src/test`）。
    //
    // 以前客户端**没有任何单测** —— 文案与几何全靠人眼评审，
    // 连续三轮评审都只能靠「读源码 + 算」发现问题
    //（比如 v0.36.0 那个「上次实测取错场次」的 P0，
    //  如果当时有单测，第一版就能挡住）。
    //
    // `isReturnDefaultValues = true`：`android.jar` 的桩方法默认抛异常，
    // 而纯逻辑测试偶尔会碰到它们（比如 `TextUtils`）。
    testOptions {
        unitTests.isReturnDefaultValues = true
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
    // ⚠️ **v0.37.0：JVM 单测**（junit 4.13.2 已在 Gradle 缓存里，离线可跑）。
    testImplementation("junit:junit:4.13.2")
}
