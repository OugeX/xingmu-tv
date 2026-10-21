package com.tvbox.desktop.player

import com.tvbox.desktop.core.Log
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * libVLC 原生库定位。
 *
 * 查找顺序：
 *  1. 设置里手工指定的路径（VLC 安装目录 / libvlc.dll / VLC.app）
 *  2. 环境变量 TVAPP_VLC_HOME / VLC_HOME，系统属性 tvapp.vlc.home
 *  3. 各平台默认安装位置 + PATH
 *
 * 找不到时返回可读提示（含已搜索路径），UI 引导用户去安装或指定路径；不崩溃。
 */
object VlcLocator {

    enum class Platform { WINDOWS, MAC, LINUX }

    data class Runtime(
        val libDir: Path,
        val pluginDir: Path?,
        val platform: Platform,
        val origin: String,
    ) {
        val displayPath: String get() = libDir.toString()
    }

    data class Result(
        val runtime: Runtime?,
        val message: String,
        val searched: List<String>,
    ) {
        val ok: Boolean get() = runtime != null
    }

    private val platform: Platform by lazy {
        val os = System.getProperty("os.name").orEmpty().lowercase()
        when {
            os.contains("win") -> Platform.WINDOWS
            os.contains("mac") -> Platform.MAC
            else -> Platform.LINUX
        }
    }

    private val libName: String
        get() = when (platform) {
            Platform.WINDOWS -> "libvlc.dll"
            Platform.MAC -> "libvlc.dylib"
            Platform.LINUX -> "libvlc.so"
        }

    fun locate(override: String? = null): Result {
        val searched = mutableListOf<String>()
        val candidates = LinkedHashMap<String, Path>()

        fun offer(path: Path?, origin: String) {
            if (path == null) return
            val normalized = runCatching { path.toAbsolutePath().normalize() }.getOrNull() ?: return
            searched += "$origin -> $normalized"
            candidates.putIfAbsent(normalized.toString(), normalized)
        }

        // 1) 用户指定
        if (!override.isNullOrBlank()) {
            val path = Paths.get(override.trim())
            offer(path, "设置")
            if (Files.isRegularFile(path)) offer(path.parent, "设置（文件父目录）")
            offer(path.resolve("lib"), "设置/lib")
            offer(path.resolve("Contents/MacOS"), "设置/Contents/MacOS")
            offer(path.resolve("Contents/MacOS/lib"), "设置/Contents/MacOS/lib")
        }

        // 2) 环境变量 / 系统属性
        listOfNotNull(
            System.getenv("TVAPP_VLC_HOME") to "TVAPP_VLC_HOME",
            System.getenv("VLC_HOME") to "VLC_HOME",
            System.getProperty("tvapp.vlc.home") to "-Dtvapp.vlc.home",
        ).forEach { (value, origin) ->
            if (!value.isNullOrBlank()) {
                val path = Paths.get(value)
                offer(path, origin)
                if (Files.isRegularFile(path)) offer(path.parent, "$origin（文件父目录）")
                offer(path.resolve("lib"), "$origin/lib")
            }
        }

        // 3) 平台默认位置
        when (platform) {
            Platform.WINDOWS -> {
                val programFiles = System.getenv("ProgramFiles")
                val programFilesX86 = System.getenv("ProgramFiles(x86)")
                val localAppData = System.getenv("LOCALAPPDATA")
                listOfNotNull(
                    programFiles?.let { Paths.get(it, "VideoLAN", "VLC") },
                    programFilesX86?.let { Paths.get(it, "VideoLAN", "VLC") },
                    localAppData?.let { Paths.get(it, "Programs", "VideoLAN", "VLC") },
                    localAppData?.let { Paths.get(it, "VideoLAN", "VLC") },
                ).forEach { offer(it, "默认安装目录") }
            }
            Platform.MAC -> {
                offer(Paths.get("/Applications/VLC.app/Contents/MacOS/lib"), "默认安装目录")
                offer(Paths.get("/Applications/VLC.app/Contents/MacOS"), "默认安装目录")
                offer(Paths.get("/opt/homebrew/lib"), "Homebrew")
                offer(Paths.get("/usr/local/lib"), "Homebrew")
            }
            Platform.LINUX -> {
                offer(Paths.get("/usr/lib/x86_64-linux-gnu"), "系统库目录")
                offer(Paths.get("/usr/lib"), "系统库目录")
                offer(Paths.get("/usr/local/lib"), "系统库目录")
            }
        }

        // 4) PATH 中的 vlc/bin 目录与工作目录下的 vlc/
        System.getenv("PATH").orEmpty().split(java.io.File.pathSeparator)
            .filter { it.isNotBlank() }
            .forEach { entry ->
                runCatching { Paths.get(entry) }.getOrNull()?.let { offer(it, "PATH") }
            }
        offer(Paths.get(System.getProperty("user.dir"), "vlc"), "工作目录/vlc")

        for (candidate in candidates.values) {
            val lib = resolveLibFile(candidate)
            if (lib != null) {
                val libDir = lib.parent
                val pluginDir = pluginDirFor(libDir)
                Log.i("Vlc", "libVLC 命中：$lib（plugins=${pluginDir ?: "默认"}）")
                return Result(Runtime(libDir, pluginDir, platform, candidate.toString()), "OK", searched)
            }
        }

        val hint = when (platform) {
            Platform.WINDOWS -> "下载并安装 VLC 3.x 64 位桌面版（https://www.videolan.org/vlc/），或在设置里指定 VLC 安装目录"
            Platform.MAC -> "安装 VLC.app 到 /Applications（https://www.videolan.org/vlc/），或在设置里指定 VLC.app 路径"
            Platform.LINUX -> "安装 libvlc（例如 apt install libvlc-dev vlc-plugin-base），或在设置里指定 libvlc 所在目录"
        }
        return Result(
            null,
            "未找到 $libName。$hint；也可以设置环境变量 TVAPP_VLC_HOME 指向 VLC 目录。",
            searched,
        )
    }

    /** 在候选目录里找 libvlc 动态库（兼容 VLC.app 的 MacOS/lib 布局）。 */
    private fun resolveLibFile(candidate: Path): Path? {
        val direct = candidate.resolve(libName)
        if (Files.isRegularFile(direct)) return direct
        val nested = candidate.resolve("lib").resolve(libName)
        if (Files.isRegularFile(nested)) return nested
        return null
    }

    private fun pluginDirFor(libDir: Path): Path? {
        val direct = libDir.resolve("plugins")
        if (Files.isDirectory(direct)) return direct
        val parent = libDir.parent?.resolve("plugins")
        if (parent != null && Files.isDirectory(parent)) return parent
        return null
    }

    /** 必须在创建 MediaPlayerFactory 之前调用。 */
    fun configure(runtime: Runtime) {
        val dir = runtime.libDir.toString()
        val existing = System.getProperty("jna.library.path").orEmpty()
        val paths = buildList {
            add(dir)
            existing.split(java.io.File.pathSeparator).filter { it.isNotBlank() }.forEach { add(it) }
        }.distinct().joinToString(java.io.File.pathSeparator)
        System.setProperty("jna.library.path", paths)
        val bootExisting = System.getProperty("jna.boot.library.path").orEmpty()
        if (bootExisting.isBlank()) System.setProperty("jna.boot.library.path", dir)
        Log.i("Vlc", "jna.library.path=$paths")
    }
}
