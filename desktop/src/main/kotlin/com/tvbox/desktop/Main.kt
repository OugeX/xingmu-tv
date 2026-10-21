package com.tvbox.desktop

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.tvbox.desktop.data.AppPaths
import com.tvbox.desktop.smoke.SmokeRunner
import com.tvbox.desktop.ui.AppRoot
import com.tvbox.desktop.ui.AppViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import java.awt.Dimension
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.system.exitProcess

/**
 * 星幕 TV 桌面端入口。
 *
 * 常用参数：
 *   --smoke                 非交互自检（数据链路 + libVLC 实播直播/点播），结果写 JSON
 *   --smoke-out <path>      自检结果输出路径
 *   --assets <dir>          指定仓库 assets/ 目录
 *   --vlc <path>            指定 VLC 安装目录 / libvlc 文件
 *   --autoplay-live         启动后自动播第一条可用直播（演示/截图用）
 */
fun main(args: Array<String>) {
    val options = LaunchOptions.parse(args)
    if (options.help) {
        println(LaunchOptions.USAGE)
        return
    }
    val paths = AppPaths.resolve(options.assetsDir)
    paths.ensureDirectories()

    if (options.smoke) {
        if (options.vlcHome != null) System.setProperty("tvapp.vlc.home", options.vlcHome)
        val out = options.smokeOut ?: paths.workingDir.parent
            ?.resolve(".artifacts/desktop/smoke.json")
            ?: Paths.get("smoke.json")
        val ok = runBlocking { SmokeRunner(paths, out).run() }
        exitProcess(if (ok) 0 else 1)
    }

    application {
        val scope = remember { CoroutineScope(SupervisorJob() + Dispatchers.Main) }
        val vm = remember {
            AppViewModel(
                paths = paths,
                scope = scope,
                autoplayLive = options.autoplayLive,
                vlcHomeArg = options.vlcHome,
            )
        }
        val windowState = rememberWindowState(width = 1440.dp, height = 900.dp)

        fun shutdown() {
            vm.shutdown()
            scope.cancel()
            exitApplication()
        }

        Window(
            onCloseRequest = ::shutdown,
            state = windowState,
            title = "星幕 TV",
        ) {
            LaunchedEffect(Unit) {
                window.minimumSize = Dimension(1180, 720)
            }
            AppRoot(vm, windowState) { shutdown() }
        }
    }
}

data class LaunchOptions(
    val smoke: Boolean = false,
    val smokeOut: Path? = null,
    val assetsDir: String? = null,
    val vlcHome: String? = null,
    val autoplayLive: Boolean = false,
    val help: Boolean = false,
) {
    companion object {
        const val USAGE = """
星幕 TV（桌面端）
用法: gradle run --args="[options]"
  --smoke              运行非交互自检并退出（退出码 0=全部通过）
  --smoke-out <file>   自检结果 JSON 输出路径
  --assets <dir>       指定仓库 assets/ 目录（默认自动向上查找）
  --vlc <path>         libVLC 目录/文件（也可用环境变量 TVAPP_VLC_HOME）
  --autoplay-live      启动后自动播放第一条可用直播（演示/截图用）
  --help               显示帮助
"""

        fun parse(args: Array<String>): LaunchOptions {
            var smoke = false
            var smokeOut: Path? = null
            var assets: String? = null
            var vlc: String? = null
            var autoplay = false
            var help = false
            var i = 0
            while (i < args.size) {
                when (val arg = args[i]) {
                    "--smoke" -> smoke = true
                    "--smoke-out" -> if (i + 1 < args.size) smokeOut = Paths.get(args[++i])
                    "--assets" -> if (i + 1 < args.size) assets = args[++i]
                    "--vlc" -> if (i + 1 < args.size) vlc = args[++i]
                    "--autoplay-live" -> autoplay = true
                    "--help", "-h" -> help = true
                    else -> when {
                        arg.startsWith("--smoke-out=") -> smokeOut = Paths.get(arg.substringAfter('='))
                        arg.startsWith("--assets=") -> assets = arg.substringAfter('=')
                        arg.startsWith("--vlc=") -> vlc = arg.substringAfter('=')
                    }
                }
                i++
            }
            return LaunchOptions(smoke, smokeOut, assets, vlc, autoplay, help)
        }
    }
}
