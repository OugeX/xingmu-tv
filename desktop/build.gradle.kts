import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    kotlin("jvm") version "2.0.21"
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21"
    id("org.jetbrains.compose") version "1.7.3"
}

group = "com.tvbox.desktop"
version = "0.1.0"

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(compose.desktop.currentOs)
    implementation(compose.material)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.9.0")
    implementation("org.json:json:20240303")
    // libVLC JVM binding；原生库（libvlc.dll / libvlc.dylib）由用户机器上的 VLC 提供，
    // 见 README「PC 客户端依赖 libVLC」。
    implementation("uk.co.caprica:vlcj:4.8.2")

    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed")
    }
}

/*
 * 内置数据（源合集 / 直播种子 / 高清标记）唯一来源是仓库根目录 assets/。
 * 构建时只把它们复制进 jar 作为「打包后的兜底」，源码树里不复制、不维护第二份。
 * 运行时优先读仓库 assets/（`desktop/../assets`），便于直接改文件调试。
 */
val repoAssetsDir = rootProject.projectDir.parentFile.resolve("assets")
val stagedBuiltinDir = layout.buildDirectory.dir("generated/builtin-assets")

val stageBuiltinAssets by tasks.registering(Copy::class) {
    description = "把根目录 assets/ 的内置数据复制到构建产物，供 jar 内兜底使用"
    from(repoAssetsDir) {
        include("sources.builtin.json", "live_seed.json", "live_hd.json")
    }
    into(stagedBuiltinDir)
}

sourceSets.main {
    resources.srcDir(stageBuiltinAssets)
}

tasks.processResources {
    dependsOn(stageBuiltinAssets)
}

compose.desktop {
    application {
        mainClass = "com.tvbox.desktop.MainKt"

        nativeDistributions {
            targetFormats(TargetFormat.Msi, TargetFormat.Exe, TargetFormat.Dmg)
            packageName = "XingmuTV"
            packageVersion = "0.1.0"
            description = "星幕 TV · 直播与点播桌面客户端"
            vendor = "Xingmu TV"
            copyright = "© 2026 Xingmu TV · MIT License"

            // java.net.http = 内置 HttpClient；jdk.unsupported = JNA/libVLC 需要
            modules("java.net.http", "jdk.unsupported")

            windows {
                menuGroup = "星幕 TV"
                shortcut = true
                dirChooser = true
                perUserInstall = true
                upgradeUuid = "6c1f5c3a-7ab9-4f0c-9d61-6f8a2f0e15a7"
            }

            macOS {
                bundleID = "com.tvbox.xingmu.desktop"
                dockName = "星幕 TV"
            }
        }
    }
}
