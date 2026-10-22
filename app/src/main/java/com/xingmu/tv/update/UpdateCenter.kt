package com.xingmu.tv.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.xingmu.tv.core.AppDispatchers
import com.xingmu.tv.core.TvLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

private const val TAG = "Update"
private const val DAY_MS = 24L * 60 * 60 * 1000
private const val APK_MIME = "application/vnd.android.package-archive"

/**
 * 应用内更新中心（UPDATE_SPEC）。
 *
 * 硬约束（**访问不了就静默**）：
 * - [checkNow] 捕获一切异常，只打一行 `update check failed: <原因>`，并把状态复位为 [UpdateState.Idle]；
 * - 只有 `Available` 状态会让 UI 出现弹窗，因此网络失败时不可能出现任何弹窗/toast；
 * - 自动检查只在 Wi-Fi 且距上次 ≥ 24h 时执行；手动检查不受限。
 */
class UpdateCenter internal constructor(
    private val appContext: Context?,
    private val fetcher: ReleaseFetcher,
    private val prefs: UpdatePreferences,
    private val isWifi: () -> Boolean,
    private val freeBytes: () -> Long,
    private val now: () -> Long,
    private val currentVersion: () -> String,
    private val scope: CoroutineScope,
) {

    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)

    /** UI 唯一状态源：Available/Downloading/ReadyToInstall/Failed 才会渲染弹窗。 */
    val state = _state.asStateFlow()

    private val _events = MutableSharedFlow<UpdateUiEvent>(replay = 16, extraBufferCapacity = 16)

    /** 成功路径事件（单测断言：IOException 时 replayCache 为空）。 */
    val events = _events.asSharedFlow()

    private val checking = AtomicBoolean(false)
    private var downloadJob: Job? = null

    val lastCheckAt: Long get() = prefs.lastCheckAt

    /** 手动/自动检查；自动路径带 Wi-Fi 与 24h 节流。异常永不外抛。 */
    suspend fun checkNow(manual: Boolean, includePrerelease: Boolean): UpdateCheckResult {
        if (!manual) {
            if (!isWifi()) return UpdateCheckResult.Skipped("not wifi")
            val last = prefs.lastCheckAt
            val time = now()
            if (last > 0 && time - last < DAY_MS) return UpdateCheckResult.Skipped("throttled")
        }
        if (!checking.compareAndSet(false, true)) return UpdateCheckResult.Skipped("busy")

        val checkedAt = now()
        prefs.lastCheckAt = checkedAt
        _state.value = UpdateState.Checking
        return try {
            val release = fetcher.latest(includePrerelease) ?: throw IOException("empty response")
            val current = currentVersion()
            if (!UpdateVersion.isNewer(current, release.tag)) {
                _state.value = UpdateState.UpToDate(checkedAt)
                _events.tryEmit(UpdateUiEvent.UpToDate(checkedAt))
                UpdateCheckResult.UpToDate(checkedAt)
            } else {
                val pick = UpdateAssetSelector.select(release.assets, release.version, freeBytes())
                val candidate = UpdateCandidate(release, pick?.asset, pick?.flavor)
                _state.value = UpdateState.Available(candidate, current)
                _events.tryEmit(UpdateUiEvent.NewVersion(candidate, current))
                UpdateCheckResult.Available(candidate)
            }
        } catch (t: CancellationException) {
            _state.value = UpdateState.Idle
            throw t
        } catch (t: Throwable) {
            // 静默：不改任何 UI 状态，只留一行可检索日志（UPDATE_SPEC §1/§6）。
            _state.value = UpdateState.Idle
            TvLog.w(TAG, "update check failed: ${t.message ?: t::class.java.simpleName}")
            UpdateCheckResult.Failed(t.message ?: t::class.java.simpleName)
        } finally {
            checking.set(false)
        }
    }

    /** 冷启动后由 AppRoot 延时调用；Wi-Fi/24h 判定在 [checkNow] 内统一处理。 */
    fun autoCheckIfDue(includePrerelease: Boolean) {
        val current = _state.value
        if (current !is UpdateState.Idle && current !is UpdateState.UpToDate) return
        scope.launch { checkNow(manual = false, includePrerelease = includePrerelease) }
    }

    /** 「稍后」：关闭弹窗，并把本次检查时间写入，24h 内不再自动检查。 */
    fun dismiss() {
        downloadJob?.cancel()
        downloadJob = null
        prefs.lastCheckAt = now()
        _state.value = UpdateState.Idle
    }

    /** 「立即更新」：下载到 `cacheDir/update/`，带进度，可取消。 */
    fun startDownload() {
        val context = appContext ?: return
        val available = _state.value as? UpdateState.Available ?: return
        val candidate = available.candidate
        val asset = candidate.asset ?: return
        downloadJob?.cancel()
        downloadJob = scope.launch {
            _state.value = UpdateState.Downloading(candidate, 0L, asset.sizeBytes)
            val dir = File(context.cacheDir, "update").apply { mkdirs() }
            dir.listFiles()?.forEach { old -> if (old.isFile && old.name.endsWith(".apk")) old.delete() }
            val target = File(dir, asset.name.replace('/', '_'))
            try {
                val file = ApkDownloader.download(asset.downloadUrl, target, asset.sizeBytes) { read, total ->
                    _state.value = UpdateState.Downloading(candidate, read, total)
                }
                _state.value = UpdateState.ReadyToInstall(
                    candidate = candidate,
                    file = file,
                    needPermission = !UpdateEnvironment.canInstallPackages(context),
                )
            } catch (cancel: CancellationException) {
                target.delete()
                _state.value = UpdateState.Available(candidate, currentVersion())
                throw cancel
            } catch (t: Throwable) {
                target.delete()
                TvLog.w(TAG, "update download failed: ${t.message ?: t::class.java.simpleName}")
                _state.value = UpdateState.Failed("更新包下载失败，可稍后重试")
            }
        }
    }

    fun cancelDownload() {
        val current = _state.value as? UpdateState.Downloading ?: return
        downloadJob?.cancel()
        downloadJob = null
        _state.value = UpdateState.Available(current.candidate, currentVersion())
    }

    /** 安装：API>=26 未授权时切到 [UpdateState.ReadyToInstall.needPermission]，由 UI 引导去系统设置。 */
    fun installDownloaded() {
        val context = appContext ?: return
        val ready = _state.value as? UpdateState.ReadyToInstall ?: return
        if (!UpdateEnvironment.canInstallPackages(context)) {
            _state.value = ready.copy(needPermission = true)
            return
        }
        val uri = runCatching {
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", ready.file)
        }.getOrElse {
            _state.value = UpdateState.Failed("安装包准备失败，请稍后重试")
            return
        }
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, APK_MIME)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        val ok = runCatching { context.startActivity(intent) }.isSuccess
        if (!ok) _state.value = UpdateState.Failed("无法调起系统安装器")
    }

    /** 跳转「安装未知来源」授权页（API 26+）。 */
    fun openInstallPermissionSettings() {
        val context = appContext ?: return
        if (Build.VERSION.SDK_INT < 26) return
        val intent = Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${context.packageName}"),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
    }

    /** 只有 Release 页面时（找不到匹配 APK）打开浏览器。 */
    fun openReleasePage(pageUrl: String) {
        val context = appContext ?: return
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(pageUrl))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
    }

    fun dismissFailure() {
        _state.value = UpdateState.Idle
    }

    companion object Factory {
        @Volatile
        private var instance: UpdateCenter? = null

        fun get(context: Context): UpdateCenter {
            instance?.let { return it }
            return synchronized(this) {
                instance ?: build(context.applicationContext).also { instance = it }
            }
        }

        private fun build(context: Context): UpdateCenter = UpdateCenter(
            appContext = context,
            fetcher = GitHubReleaseClient(userAgent = "XingmuTV/${AppVersion.name(context)}"),
            prefs = SharedPrefsUpdatePreferences(context),
            isWifi = { UpdateEnvironment.isWifi(context) },
            freeBytes = { UpdateEnvironment.freeBytes(context) },
            now = System::currentTimeMillis,
            currentVersion = { AppVersion.name(context) },
            scope = CoroutineScope(SupervisorJob() + AppDispatchers.Io + CoroutineName("update")),
        )
    }
}
