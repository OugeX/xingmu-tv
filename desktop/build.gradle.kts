import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import java.security.MessageDigest

plugins {
    kotlin("jvm") version "2.0.21"
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21"
    id("org.jetbrains.compose") version "1.7.3"
}

group = "com.tvbox.desktop"
version = "0.2.0"
val appVersion = version.toString()

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

/*
 * 版本号不硬编码在 UI：构建时生成 version.properties，BuildInfo 从 classpath 读取。
 */
val generatedVersionDir = layout.buildDirectory.dir("generated/version-resources")
val generateVersionResource by tasks.registering {
    val outputDir = generatedVersionDir
    val buildVersion = appVersion
    inputs.property("version", buildVersion)
    outputs.dir(outputDir)
    doLast {
        val file = outputDir.get().file("XingmuTV-version.properties").asFile
        file.parentFile.mkdirs()
        file.writeText("version=$buildVersion\n")
    }
}

sourceSets.main {
    resources.srcDir(generatedVersionDir)
}

tasks.processResources {
    dependsOn(stageBuiltinAssets, generateVersionResource)
}

/*
 * 把 Windows 安装包按版本号复制到仓库根 release/，并打印 size + sha256。
 * 用法：./gradlew stageRelease
 */
val releaseDir = rootProject.projectDir.parentFile.resolve("release")
val stageRelease by tasks.registering(Copy::class) {
    group = "distribution"
    description = "复制 MSI/EXE 到仓库 release/（XingmuTV-<version>.*）并打印校验值"
    dependsOn("packageMsi", "packageExe")
    from(layout.buildDirectory.dir("compose/binaries/main/msi")) {
        include("*.msi")
        rename { "XingmuTV-$appVersion.msi" }
    }
    from(layout.buildDirectory.dir("compose/binaries/main/exe")) {
        include("*.exe")
        rename { "XingmuTV-$appVersion.exe" }
    }
    into(releaseDir)
    doLast {
        releaseDir.listFiles { file ->
            file.isFile && file.name.startsWith("XingmuTV-$appVersion") &&
                (file.extension == "msi" || file.extension == "exe")
        }?.sortedBy { it.name }?.forEach { file ->
            val sha256 = MessageDigest.getInstance("SHA-256")
                .digest(file.readBytes())
                .joinToString("") { byte: Byte -> "%02x".format(byte) }
            println("RELEASE ${file.absolutePath} size=${file.length()} sha256=$sha256")
        }
    }
}

compose.desktop {
    application {
        mainClass = "com.tvbox.desktop.MainKt"
        jvmArgs += listOf("-Dfile.encoding=UTF-8", "-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8")

        nativeDistributions {
            targetFormats(TargetFormat.Msi, TargetFormat.Exe, TargetFormat.Dmg)
            packageName = "XingmuTV"
            packageVersion = appVersion
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
                // jpackage 的 macOS 版本号要求 MAJOR > 0；应用内展示的版本取自构建（0.2.0）
                packageVersion = "1.0.0"
                dmgPackageVersion = "1.0.0"
            }
        }
    }
}
