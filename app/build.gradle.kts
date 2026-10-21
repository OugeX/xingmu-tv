plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

/*
 * 版本基线（docs/TOOLCHAIN.md，禁止随手升级）：
 *   AGP 8.5.2 / Kotlin 2.0.21 / Gradle 8.7
 *   Compose BOM 2024.09.03（仅 ui + foundation，无 Material3 / tv-material / Leanback）
 *   Media3 1.5.1（exoplayer / exoplayer-hls / ui / datasource-okhttp）
 *   OkHttp 4.12.0 / Coil 2.7.0 / zxing-core 3.5.3
 *
 * compileSdk = 35 的唯一原因：Media3 1.5.1 的 AAR 元数据要求 minCompileSdk=35
 * （android-35 已安装在 D:\tvbuild\android-sdk）。targetSdk 仍为 34。
 */
android {
    namespace = "com.tvbox.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.tvbox.app"
        minSdk = 21
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"

        // 只保留 zh/en，减小包体；与 docs/PLAN_ARCHITECTURE.md §包体控制一致。
        resourceConfigurations += listOf("zh", "en")
    }

    buildFeatures {
        compose = true
        buildConfig = false
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            // 空骨架阶段不做 R8，避免未验证的裁剪规则影响另外两条实现线；
            // 包体达标（≤14MB）由 t5/qa 在 release 阶段统一开启 R8 + 资源裁剪后复测。
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    packaging {
        resources.excludes += setOf(
            "META-INF/AL2.0",
            "META-INF/LGPL2.1",
            "META-INF/DEPENDENCIES",
            "META-INF/LICENSE*",
            "META-INF/NOTICE*",
        )
    }
}

dependencies {
    // ---- Compose：只用 foundation + ui（不引 material3 / tv-material） ----
    implementation(platform("androidx.compose:compose-bom:2024.09.03"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    // ComponentActivity.setContent（不是 Material 依赖，不违反冻结决策）
    implementation("androidx.activity:activity-compose:1.9.2")

    // ---- 协程：共享 Dispatcher / StateFlow 契约 ----
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // ---- Media3 播放内核 ----
    implementation("androidx.media3:media3-exoplayer:1.5.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.5.1")
    implementation("androidx.media3:media3-ui:1.5.1")
    implementation("androidx.media3:media3-datasource-okhttp:1.5.1")

    // ---- 网络 / 图片 / 扫码（局域网导入） ----
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("io.coil-kt:coil:2.7.0")
    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("com.google.zxing:core:3.5.3")
}
