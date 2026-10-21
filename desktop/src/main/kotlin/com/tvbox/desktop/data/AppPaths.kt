package com.tvbox.desktop.data

import com.tvbox.desktop.core.Log
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * 跨平台路径：全部走 java.nio，不写死盘符。
 *
 * - 配置：Windows %APPDATA%\XingmuTV；macOS ~/Library/Application Support/XingmuTV；Linux $XDG_CONFIG_HOME
 * - 缓存：Windows %LOCALAPPDATA%\XingmuTV\cache；macOS ~/Library/Caches/XingmuTV；Linux $XDG_CACHE_HOME
 * - 内置数据：优先仓库根 assets/（运行时向上查找），打包后回退 jar 内 resources。
 */
class AppPaths private constructor(
    val configDir: Path,
    val cacheDir: Path,
    val logDir: Path,
    val assetsDir: Path?,
    val assetsSource: String,
    val workingDir: Path,
) {
    val userSourcesFile: Path get() = configDir.resolve("sources.user.json")
    val settingsFile: Path get() = configDir.resolve("settings.json")

    fun ensureDirectories() {
        listOf(configDir, cacheDir, logDir).forEach { dir ->
            runCatching { Files.createDirectories(dir) }
                .onFailure { Log.w("Paths", "无法创建目录 $dir: ${it.message}") }
        }
    }

    /**
     * 读取内置数据：仓库 assets/ 优先；打包运行（无 assets/）时回退 jar 内同名资源。
     * @return 文本 + 来源描述
     */
    fun readBundled(name: String): Pair<String, String>? {
        val external = assetsDir?.resolve(name)
        if (external != null && Files.isRegularFile(external)) {
            return runCatching { Files.readString(external) to external.toString() }
                .onFailure { Log.w("Paths", "读取 $external 失败: ${it.message}") }
                .getOrNull()
        }
        val stream = AppPaths::class.java.getResourceAsStream("/$name") ?: return null
        return runCatching {
            stream.bufferedReader(Charsets.UTF_8).use { it.readText() } to "jar:/$name"
        }.getOrNull()
    }

    companion object {
        private const val APP_DIR_NAME = "XingmuTV"

        fun resolve(assetsArg: String? = null): AppPaths {
            val os = System.getProperty("os.name").orEmpty().lowercase()
            val home = Paths.get(System.getProperty("user.home"))
            val config: Path
            val cache: Path
            when {
                os.contains("win") -> {
                    val appData = System.getenv("APPDATA")?.takeIf { it.isNotBlank() }?.let { Paths.get(it) }
                        ?: home.resolve("AppData/Roaming")
                    val local = System.getenv("LOCALAPPDATA")?.takeIf { it.isNotBlank() }?.let { Paths.get(it) }
                        ?: home.resolve("AppData/Local")
                    config = appData.resolve(APP_DIR_NAME)
                    cache = local.resolve(APP_DIR_NAME).resolve("cache")
                }
                os.contains("mac") -> {
                    config = home.resolve("Library/Application Support/$APP_DIR_NAME")
                    cache = home.resolve("Library/Caches/$APP_DIR_NAME")
                }
                else -> {
                    val xdgConfig = System.getenv("XDG_CONFIG_HOME")?.takeIf { it.isNotBlank() }?.let { Paths.get(it) }
                        ?: home.resolve(".config")
                    val xdgCache = System.getenv("XDG_CACHE_HOME")?.takeIf { it.isNotBlank() }?.let { Paths.get(it) }
                        ?: home.resolve(".cache")
                    config = xdgConfig.resolve("xingmu-tv")
                    cache = xdgCache.resolve("xingmu-tv")
                }
            }

            val workingDir = Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize()
            val (assetsDir, source) = resolveAssets(assetsArg, workingDir)
            return AppPaths(
                configDir = config,
                cacheDir = cache,
                logDir = config.resolve("logs"),
                assetsDir = assetsDir,
                assetsSource = source,
                workingDir = workingDir,
            )
        }

        private fun resolveAssets(arg: String?, workingDir: Path): Pair<Path?, String> {
            val explicit = arg
                ?: System.getenv("TVAPP_ASSETS_DIR")?.takeIf { it.isNotBlank() }
                ?: System.getProperty("tvapp.assets")
            if (!explicit.isNullOrBlank()) {
                val path = Paths.get(explicit).toAbsolutePath().normalize()
                return if (Files.isRegularFile(path.resolve("sources.builtin.json"))) {
                    path to "参数指定：$path"
                } else {
                    Log.w("Paths", "指定的 assets 目录缺少 sources.builtin.json：$path")
                    path to "参数指定（缺少 sources.builtin.json）：$path"
                }
            }

            var dir: Path? = workingDir
            var depth = 0
            while (dir != null && depth < 6) {
                val candidate = dir.resolve("assets")
                if (Files.isRegularFile(candidate.resolve("sources.builtin.json"))) {
                    return candidate to "自动发现：$candidate"
                }
                dir = dir.parent
                depth++
            }
            return null to "未找到仓库 assets/（将只用 jar 内兜底数据）"
        }
    }
}
