package com.tvbox.desktop.update

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.tvbox.desktop.core.Log
import com.tvbox.desktop.data.Http
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.IOException
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 桌面端更新中心（与 Android update/ 语义一致）：
 *  - 检查失败只写一行日志并回 [UpdateState.Idle]，不弹窗、不 toast、不自动重试；
 *  - 版本新旧判定用「资产版本」vs 当前运行版本；tag 只用于展示与 Release 页面；
 *  - 自动检查默认 8s 后一次、之后 24h 一次（由调用方循环，且 autoCheckUpdate 开关打开）；
 *  - 下载失败删半成品，只重试一次；第二次失败回 Idle 并写日志；
 *  - 不自动执行安装包，必须用户点「打开安装包」。
 */
class UpdateCenter(
    private val fetcher: ReleaseFetcher,
    private val http: Http,
    private val updateDir: Path,
    private val currentVersion: () -> String,
    private val windows: () -> Boolean = { AssetPicker.isWindowsPlatform() },
    private val lastCheckAt: () -> Long,
    private val onCheckedAt: (Long) -> Unit,
    private val autoCheckEnabled: () -> Boolean = { true },
    private val scope: CoroutineScope,
    private val now: () -> Long = System::currentTimeMillis,
) {
    var state by mutableStateOf<UpdateState>(UpdateState.Idle)
        private set

    private val eventList = mutableListOf<UpdateUiEvent>()
    private val checking = AtomicBoolean(false)
    private var downloadJob: Job? = null

    fun eventsSnapshot(): List<UpdateUiEvent> = synchronized(eventList) { eventList.toList() }

    /** 手动/自动检查；异常永不外抛（取消除外），状态回 Idle。 */
    suspend fun checkNow(manual: Boolean, includePrerelease: Boolean = false): UpdateCheckResult {
        if (!manual) {
            if (!autoCheckEnabled()) return UpdateCheckResult.Skipped("auto disabled")
            val last = lastCheckAt()
            if (last > 0L && now() - last < DAY_MS) return UpdateCheckResult.Skipped("throttled")
        }
        if (!checking.compareAndSet(false, true)) return UpdateCheckResult.Skipped("busy")

        val checkedAt = now()
        onCheckedAt(checkedAt)
        state = UpdateState.Checking
        return try {
            val release = fetcher.latest(includePrerelease) ?: throw IOException("empty release")
            val current = currentVersion()
            val picked = AssetPicker.best(release.assets, windows())

            if (picked != null) {
                if (DesktopVersion.isNewer(current, picked.version)) {
                    val candidate = UpdateCandidate(release, picked)
                    state = UpdateState.Available(candidate, current)
                    emit(UpdateUiEvent.NewVersion(candidate, current))
                    UpdateCheckResult.Available(candidate)
                } else {
                    state = UpdateState.UpToDate(checkedAt)
                    emit(UpdateUiEvent.UpToDate(checkedAt))
                    UpdateCheckResult.UpToDate(checkedAt)
                }
            } else if (DesktopVersion.isNewer(current, release.version)) {
                // 没有桌面包，但 tag 比当前新：只提供 Release 页面。
                val candidate = UpdateCandidate(release, desktopAsset = null)
                state = UpdateState.Available(candidate, current)
                emit(UpdateUiEvent.NewVersion(candidate, current))
                UpdateCheckResult.Available(candidate)
            } else {
                state = UpdateState.Idle
                UpdateCheckResult.Skipped("no desktop asset")
            }
        } catch (cancel: CancellationException) {
            state = UpdateState.Idle
            throw cancel
        } catch (t: Throwable) {
            // 静默失败：没有 UI 事件，只留日志。
            state = UpdateState.Idle
            Log.w("Update", "update check failed: ${t.message ?: t.javaClass.simpleName}")
            UpdateCheckResult.Failed(t.message ?: t.javaClass.simpleName)
        } finally {
            checking.set(false)
        }
    }

    /** 由 AppViewModel 的 8s/24h 循环调用；内部再做 24h 节流与状态保护。 */
    fun autoCheckIfDue(includePrerelease: Boolean = false) {
        if (!autoCheckEnabled()) return
        val current = state
        if (current !is UpdateState.Idle && current !is UpdateState.UpToDate) return
        scope.launch { checkNow(manual = false, includePrerelease = includePrerelease) }
    }

    /** 下载当前 Available 候选；完成后进入 Ready(file)，等待用户点「打开安装包」。 */
    fun startDownload() {
        val available = state as? UpdateState.Available ?: return
        val asset = available.candidate.desktopAsset ?: return
        downloadJob?.cancel()
        downloadJob = scope.launch {
            state = UpdateState.Downloading(available.candidate, 0L, asset.asset.sizeBytes)
            try {
                val outcome = UpdateDownloader.download(
                    http = http,
                    asset = asset.asset,
                    target = updateDir.resolve(UpdateDownloader.sanitizeFileName(asset.asset.name)),
                    kind = asset.kind,
                    onProgress = { received, total ->
                        state = UpdateState.Downloading(available.candidate, received, total)
                    },
                    cancelled = { !isActive },
                )
                state = UpdateState.Ready(outcome.file, available.candidate)
                Log.i("Update", "update downloaded: ${outcome.file} (${outcome.bytes} bytes, attempts=${outcome.attempts})")
            } catch (cancel: CancellationException) {
                state = UpdateState.Available(available.candidate, currentVersion())
                throw cancel
            } catch (t: Throwable) {
                // 下载器已重试一次；第二次失败回 Idle，静默（只写日志）。
                Log.w("Update", "update download failed: ${t.message ?: t.javaClass.simpleName}")
                state = UpdateState.Idle
            }
        }
    }

    fun cancelDownload() {
        val downloading = state as? UpdateState.Downloading ?: return
        downloadJob?.cancel()
        downloadJob = null
        state = UpdateState.Available(downloading.candidate, currentVersion())
    }

    /** 用户点击「打开安装包」：交给系统安装器，不静默执行。 */
    fun openInstaller(): Boolean {
        val ready = state as? UpdateState.Ready ?: return false
        return runCatching {
            if (!java.awt.Desktop.isDesktopSupported()) throw IOException("Desktop API 不可用")
            java.awt.Desktop.getDesktop().open(ready.file.toFile())
            true
        }.getOrElse {
            Log.w("Update", "open installer failed: ${it.message}")
            state = UpdateState.Idle
            false
        }
    }

    fun openReleasePage(pageUrl: String): Boolean = runCatching {
        if (!java.awt.Desktop.isDesktopSupported()) throw IOException("Desktop API 不可用")
        java.awt.Desktop.getDesktop().browse(java.net.URI(pageUrl))
        true
    }.getOrElse {
        Log.w("Update", "open release page failed: ${it.message}")
        false
    }

    fun dismissFailure() {
        if (state is UpdateState.Failed) state = UpdateState.Idle
    }

    private fun emit(event: UpdateUiEvent) {
        synchronized(eventList) { eventList.add(event) }
    }

    private companion object {
        const val DAY_MS = 24L * 60 * 60 * 1000
    }
}
