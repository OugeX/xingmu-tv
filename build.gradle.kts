// 根构建脚本：只声明插件版本，不在此处应用。
// 版本基线见 docs/TOOLCHAIN.md（AGP 8.5.2 / Kotlin 2.0.21 / Gradle 8.7），禁止随手升级。
plugins {
    id("com.android.application") version "8.5.2" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21" apply false
}
