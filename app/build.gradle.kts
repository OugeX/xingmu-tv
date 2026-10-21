plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    // t24：Baseline Profile 接入（消费 :baselineprofile 生成器产出的 profile）
    id("androidx.baselineprofile")
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

    // 内测发布包：复用本机 debug keystore（密钥不入仓）。正式分发换成 CI 注入证书。
    // 必须定义在 buildTypes 之前，release 才能 getByName("release")。
    signingConfigs {
        create("release") {
            val ks = file("${System.getProperty("user.home")}/.android/debug.keystore")
            if (ks.exists()) {
                storeFile = ks
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            // t24：R8 代码裁剪 + 资源裁剪（包体 12.4MB → ~8MB，冷启动类加载变少）
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("release")
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

    // ---- Baseline Profile 安装器（t24）：API 28+ 安装期应用 assets/dexopt/baseline.prof ----
    implementation("androidx.profileinstaller:profileinstaller:1.3.1")

    // t24：profile 生成器（com.android.test 模块，仅在 benchmark/生成任务里用到）
    baselineProfile(project(":baselineprofile"))
}
