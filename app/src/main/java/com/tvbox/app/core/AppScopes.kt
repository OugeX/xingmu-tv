package com.tvbox.app.core

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 线程模型（冻结，见 docs/PLAN_ARCHITECTURE.md §3.3）：
 *
 * | 工作 | Dispatcher |
 * | --- | --- |
 * | Compose / 输入 / 焦点 | 主线程（不切） |
 * | JSON 解析 / 文件缓存 | [AppDispatchers.IoLimited]（最多 2 并发） |
 * | 网络（OkHttp 自带线程池） | [AppDispatchers.Io] |
 * | 图片解码 | Coil dispatcher（2 路，由 AppGraph 配置，不在此处） |
 *
 * 约定：
 * - **Repository 的 suspend 函数自己用 `withContext(...)` 切线程**，调用方（UI）不需要切；
 * - 禁止 `GlobalScope`；应用级任务用 [AppScopes.main] / [AppScopes.io]；
 * - 页面级任务由后续 ViewModel/Composable 自己创建 scope，退出时 cancel；
 * - 播放器只在 playback 线程内部工作，主线程只读 [com.tvbox.app.player.PlayerState]。
 */
object AppDispatchers {
    val Main: CoroutineDispatcher = Dispatchers.Main.immediate
    val Io: CoroutineDispatcher = Dispatchers.IO
    val Default: CoroutineDispatcher = Dispatchers.Default

    @OptIn(ExperimentalCoroutinesApi::class)
    private val ioLimitedDelegate: CoroutineDispatcher by lazy { Dispatchers.IO.limitedParallelism(2) }

    /** 解析 / 落盘专用：最多 2 并发，避免和图片解码抢 CPU。 */
    val IoLimited: CoroutineDispatcher get() = ioLimitedDelegate
}

/** 应用级协程作用域：进程存活期间有效，仅用于“首帧后后台任务”（缓存清理、源刷新等）。 */
object AppScopes {
    private val mainScope = CoroutineScope(SupervisorJob() + AppDispatchers.Main + CoroutineName("app-main"))
    private val ioScope = CoroutineScope(SupervisorJob() + AppDispatchers.Io + CoroutineName("app-io"))

    val main: CoroutineScope get() = mainScope

    val io: CoroutineScope get() = ioScope

    fun launch(block: suspend CoroutineScope.() -> Unit): Job = mainScope.launch(block = block)

    fun launchIo(block: suspend CoroutineScope.() -> Unit): Job = ioScope.launch(block = block)
}
