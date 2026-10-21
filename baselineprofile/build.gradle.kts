plugins {
    id("com.android.test")
    id("org.jetbrains.kotlin.android")
}

/*
 * t24：Baseline Profile 生成器（com.android.test 模块，自我插桩，不产出线上 APK）。
 *
 * 生成命令（需要一台 API 28+ 真机/模拟器，当前团队的 LeTV 是 API 23，见 PENDING_DEVICE）：
 *   gradlew :app:generateBaselineProfile
 *   # 产物：app/src/main/generated/baselineProfiles/baseline-prof.txt
 *   # 拷到 app/src/main/baseline-prof.txt 后重新 assembleRelease 即打进 assets/dexopt/baseline.prof
 *
 * 说明：BaselineProfileRule 依赖 API 28+ 的 profile 采集能力，API 23 无法生成；
 * 本地只验证本模块能配置、能编译（assembleBenchmark），不伪造启动数字。
 */
android {
    namespace = "com.tvbox.app.baselineprofile"
    compileSdk = 35

    defaultConfig {
        // Baseline Profile 采集需要 API 28+（ART 侧的 baseline profile 支持从 P 开始）
        minSdk = 28
        targetSdk = 34
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    targetProjectPath = ":app"
    // 自我插桩：benchmark 直接驱动目标 app 的 release 变体
    experimentalProperties["android.experimental.self-instrumenting"] = true

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

// 说明：不裁剪变体。:app 侧由 baselineprofile 插件注入 nonMinifiedRelease，
// 生成任务（:app:generateBaselineProfile）会自动挑选匹配的测试变体。

dependencies {
    implementation("androidx.test.ext:junit:1.2.1")
    implementation("androidx.test:runner:1.6.2")
    implementation("androidx.test:rules:1.6.1")
    implementation("androidx.test.uiautomator:uiautomator:2.3.0")
    implementation("androidx.benchmark:benchmark-macro-junit4:1.2.4")
}
