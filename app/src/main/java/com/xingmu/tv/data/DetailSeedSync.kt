package com.xingmu.tv.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.xingmu.tv.core.AppDispatchers
import com.xingmu.tv.core.TvLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** 预置详情包同步状态（设置页展示）。 */
sealed interface SeedSyncState {
    data object Idle : SeedSyncState
    data class Downloading(val read: Long, val total: Long) : SeedSyncState
    data object Verifying : SeedSyncState
    data class Importing(val count: Int) : SeedSyncState
    data class Ready(val count: Int, val importMs: Long, val source: String) : SeedSyncState
    data class Failed(val message: String) : SeedSyncState
}

/**
 * 首启下载 + 导入预置详情包（t27）。
 *
 * 规则（docs/PERF_ROADMAP.md「预置详情包」）：
 * - 只在 Wi-Fi、且用户没关「使用预置详情包」时下载；
 * - 378KB zip，校验 manifest 的 detail_seed_bytes + sha256 前缀；
 * - 解压到 filesDir/detail_seed/，导入 SQLite（单事务，500 条预算 ≤1s）；
 * - 失败静默重试一次，绝不阻塞首帧（全部在 IO 协程）。
 */
class DetailSeedSync private constructor(
    private val context: Context,
    private val store: DetailSeedStore,
    private val prefs: DetailSeedPrefs,
) {

    private val scope = CoroutineScope(SupervisorJob() + AppDispatchers.Io)
    private val _state = MutableStateFlow<SeedSyncState>(SeedSyncState.Idle)
    val state: StateFlow<SeedSyncState> = _state.asStateFlow()

    @Volatile
    private var running = false

    fun stateSnapshot(): SeedSyncState = _state.value

    /**
     * 按需启动：已导入当前版本、开关关闭、非 Wi-Fi 都直接返回。
     * 重复调用安全（running 标记 + 版本判断）。
     */
    fun startIfNeeded(force: Boolean = false) {
        if (!prefs.enabled()) return
        if (running) return
        if (!force && prefs.importedVersion() == PACKAGE_VERSION && prefs.importedCount() > 0) return
        scope.launch {
            running = true
            try {
                if (!isWifi()) {
                    _state.value = SeedSyncState.Idle
                    return@launch
                }
                prefs.markAttempt()
                _state.value = SeedSyncState.Downloading(0, 0)
                var lastError: String? = null
                repeat(2) { attempt ->
                    try {
                        downloadVerifyImport()
                        return@launch
                    } catch (t: Throwable) {
                        lastError = t.message ?: t.javaClass.simpleName
                        TvLog.w("SeedSync", "预置详情包第 ${attempt + 1} 次失败: $lastError", t)
                        if (attempt == 0) _state.value = SeedSyncState.Downloading(0, 0)
                    }
                }
                prefs.markError(lastError)
                _state.value = SeedSyncState.Failed(lastError ?: "未知错误")
            } finally {
                running = false
            }
        }
    }

    /** 供单测/手动触发：下载 → 校验 → 解压 → 导入，返回导入耗时。 */
    suspend fun downloadVerifyImport(): Long = withContext(AppDispatchers.Io) {
        val dir = File(context.filesDir, DIR_NAME).apply { mkdirs() }
        val zip = File(dir, ZIP_NAME)
        if (!zip.exists() || zip.length() < MIN_ZIP_BYTES) {
            download(zip)
        }
        _state.value = SeedSyncState.Verifying
        val content = SeedPackageFiles.readZip(zip)
        val manifest = content.manifest()
        if (!SeedPackageFiles.verify(manifest, content.seedJson)) {
            // 校验失败：删包重下一次，避免半包反复失败
            zip.delete()
            throw IllegalStateException("预置包校验失败（sha256 前缀不一致）")
        }
        val pkg = content.seedPackage()
        _state.value = SeedSyncState.Importing(pkg.count)
        val importMs = store.import(pkg.toSeedEntries(), manifest.version, manifest.sourceId.ifBlank { pkg.sourceId })
        prefs.markImported(manifest.version, pkg.count)
        _state.value = SeedSyncState.Ready(pkg.count, importMs, manifest.sourceId)
        TvLog.i("SeedSync", "预置详情包导入完成 version=${manifest.version} count=${pkg.count} import=${importMs}ms")
        importMs
    }

    private suspend fun download(zip: File) {
        val tmp = File(zip.parentFile, "$ZIP_NAME.part")
        if (tmp.exists()) tmp.delete()
        val started = System.currentTimeMillis()
        val total = Http.downloadTo(PACKAGE_URL, tmp) { read, total ->
            _state.value = SeedSyncState.Downloading(read, total)
        }
        if (tmp.length() < MIN_ZIP_BYTES) throw IllegalStateException("下载体积异常：${tmp.length()}B")
        if (!tmp.renameTo(zip)) {
            tmp.copyTo(zip, overwrite = true)
            tmp.delete()
        }
        TvLog.i("SeedSync", "预置详情包下载完成 bytes=${zip.length()} in=${System.currentTimeMillis() - started}ms total=$total")
    }

    private fun isWifi(): Boolean = runCatching {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return true
        // getActiveNetwork 需要 API 23，minSdk 21 走旧接口（lint NewApi 已盯过这里）
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            val network = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(network) ?: return false
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
        } else {
            @Suppress("DEPRECATION")
            val info = cm.activeNetworkInfo ?: return false
            @Suppress("DEPRECATION")
            info.type == ConnectivityManager.TYPE_WIFI
        }
    }.getOrDefault(false)

    companion object {
        /** t26 产出包：Release v0.2.4 资产（378KB）。 */
        const val PACKAGE_URL =
            "https://github.com/OugeX/xingmu-tv/releases/download/v0.2.4/detail-seed-v1.zip"
        const val PACKAGE_VERSION = "v1"
        const val DIR_NAME = "detail_seed"
        const val ZIP_NAME = "detail-seed-v1.zip"
        private const val MIN_ZIP_BYTES = 100 * 1024L

        @Volatile
        private var instance: DetailSeedSync? = null

        fun get(context: Context): DetailSeedSync {
            instance?.let { return it }
            return synchronized(this) {
                instance ?: DetailSeedSync(
                    context.applicationContext,
                    DetailSeedStore(AndroidSeedDatabase(context.applicationContext)),
                    DetailSeedPrefs(context.applicationContext),
                ).also { instance = it }
            }
        }
    }
}
