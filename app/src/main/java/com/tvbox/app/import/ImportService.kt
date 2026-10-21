package com.tvbox.app.import

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import com.tvbox.app.core.AppDispatchers
import com.tvbox.app.core.TvLog
import com.tvbox.app.source.SourceGraph
import com.tvbox.app.source.SourceRepositoryImpl
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 扫码导入页的状态（给 Compose UI 直接渲染）。
 * @param qrBitmap 二维码（http://ip:port/i/<token>）；生成失败为 null。
 */
data class LanImportState(
    val serviceUrl: String,
    val importUrl: String,
    val pairingCode: String,
    val token: String,
    val expiresAt: Long,
    val qrBitmap: Bitmap?,
)

/**
 * 导入能力的唯一入口（t4 UI 只需要这一个类 + [ImportInputSpec]）。
 *
 * 四种通道全部汇聚到 [ImportPipeline]：
 * - 手机扫码/配对码：[startLanImport] → H5 → [LanImportServer] → pipeline；
 * - U 盘：[scanUsb] + [importUsbFile]；
 * - SAF：[SafImport.openDocumentIntent] + [importFromUri]；
 * - 遥控器兜底：[ImportInputSpec] + [importUrl]。
 *
 * 导入结果通过 [lastReport] StateFlow 广播（TV 端可从任意页面观察），
 * 源仓库通过 [repository] 暴露（列表/详情/测试/启停）。
 */
class ImportService private constructor(context: Context) {

    private val appContext = context.applicationContext

    /** 完整源仓库（SQLite + 内置源 + MacCMS 聚合），同时注册给 AppServices。 */
    val repository: SourceRepositoryImpl = SourceGraph.install(appContext)

    val pipeline = ImportPipeline(repository)
    val usbScanner = UsbImportScanner(appContext)
    private val historyStore = ImportInputSpec.history(appContext)

    fun history(): ImportHistory = historyStore

    private val _lastReport = MutableStateFlow<ImportReport?>(null)
    val lastReport: StateFlow<ImportReport?> = _lastReport.asStateFlow()

    private val _lanState = MutableStateFlow<LanImportState?>(null)
    val lanState: StateFlow<LanImportState?> = _lanState.asStateFlow()

    @Volatile
    private var server: LanImportServer? = null

    private val starting = AtomicBoolean(false)

    // ---------------------------------------------------------------- 局域网（扫码/配对码）

    /**
     * 进入导入页时调用：启动 8088 服务、生成 token + 6 位配对码 + 二维码。
     * 返回 null 表示端口全被占用（UI 提示改用 U 盘/遥控器）。
     * **必须在页面退出/进入后台时调用 [stopLanImport]**（服务是攻击面）。
     */
    fun startLanImport(): LanImportState? {
        if (_lanState.value != null) return _lanState.value
        if (!starting.compareAndSet(false, true)) return _lanState.value
        try {
            val s = LanImportServer(
                context = appContext,
                pipeline = pipeline,
                onReport = { report ->
                    _lastReport.value = report
                    historyStore.remember(report.accepted.firstOrNull()?.endpoint ?: "")
                },
            )
            val session = s.start() ?: return null
            server = s
            val state = LanImportState(
                serviceUrl = session.serviceUrl,
                importUrl = session.importUrl,
                pairingCode = session.pairingCode,
                token = session.token,
                expiresAt = session.expiresAt,
                qrBitmap = QrCodes.bitmap(session.importUrl, 420),
            )
            _lanState.value = state
            // debug 包记录一次性导入地址（含 token），便于真机联调；release 不输出（TvLog.d）
            TvLog.d(TAG, "startLanImport url=${state.importUrl} pairing=${state.pairingCode}")
            return state
        } finally {
            starting.set(false)
        }
    }

    /** 离开导入页（或页面 onStop）时调用：关闭端口、停止接收（幂等）。 */
    fun stopLanImport() {
        server?.close("页面退出")
        server = null
        _lanState.value = null
    }

    /** 刷新二维码/配对码（token 过期后用户点“重新生成”）。 */
    fun restartLanImport(): LanImportState? {
        stopLanImport()
        return startLanImport()
    }

    /** 手机是否已用配对码完成配对（TV 端配对码页显示状态用）。 */
    fun isLanPaired(): Boolean = server?.isPaired() == true

    fun isLanRunning(): Boolean = server?.isRunning == true

    /**
     * TV 端校验用户输入的 6 位配对码（配对码页“核对”用）。
     * 与服务端 [LanImportServer] 的 /api/pair 规则一致：错误返回 E_PAIR / E_PAIR_EXPIRED。
     */
    fun verifyPairingCode(input: String): com.tvbox.app.core.Result<Boolean> {
        val state = _lanState.value
            ?: return com.tvbox.app.core.Result.failure(ImportCodes.PAIR_EXPIRED, "导入服务未启动或已过期，请重新生成配对码")
        val code = input.filter { it.isDigit() }
        if (code.length != 6) {
            return com.tvbox.app.core.Result.failure(ImportCodes.PAIR, "请输入电视上显示的 6 位数字配对码")
        }
        if (System.currentTimeMillis() > state.expiresAt) {
            return com.tvbox.app.core.Result.failure(ImportCodes.PAIR_EXPIRED, "配对码已过期（5 分钟），请重新生成")
        }
        return if (code == state.pairingCode) {
            com.tvbox.app.core.Result.Ok(true)
        } else {
            com.tvbox.app.core.Result.failure(ImportCodes.PAIR, "配对码不正确，请核对电视上显示的 6 位数字")
        }
    }

    // ---------------------------------------------------------------- 统一导入入口

    suspend fun importText(text: String, options: ImportOptions = ImportOptions()): ImportReport =
        record(pipeline.importText(text, options))

    suspend fun importBytes(
        bytes: ByteArray,
        options: ImportOptions = ImportOptions(origin = ImportOrigin.FILE),
    ): ImportReport = record(pipeline.importBytes(bytes, options))

    suspend fun importUrl(url: String): ImportReport = withContext(AppDispatchers.Io) {
        val report = pipeline.importUrl(url, ImportOptions(origin = ImportOrigin.URL))
        if (report.ok) historyStore.remember(url)
        record(report)
    }

    /** SAF：ACTION_OPEN_DOCUMENT 返回的 uri。 */
    suspend fun importFromUri(uri: Uri): ImportReport = withContext(AppDispatchers.Io) {
        val name = SafImport.displayName(appContext, uri)
        val bytes = SafImport.readBytes(appContext, uri)
        if (bytes == null) {
            return@withContext record(
                ImportReport.failure(ImportOrigin.SAF, "E_IO", "无法读取文件（可能没有访问权限）"),
            )
        }
        val report = pipeline.importBytes(
            bytes,
            ImportOptions(origin = ImportOrigin.SAF, filename = name),
        )
        record(report)
    }

    /**
     * U 盘文件导入。m3u 内容会作为直播源（inline）导入；
     * .txt 支持纯 URL 列表；.json 支持单源/合集。
     */
    suspend fun importUsbFile(file: UsbSourceFile): ImportReport = withContext(AppDispatchers.Io) {
        val bytes = usbScanner.read(file)
        if (bytes == null) {
            return@withContext record(
                ImportReport.failure(ImportOrigin.USB, "E_IO", "无法读取 U 盘文件：${file.name}"),
            )
        }
        val report = pipeline.importBytes(
            bytes,
            ImportOptions(origin = ImportOrigin.USB, filename = file.name),
        )
        record(report)
    }

    fun scanUsb(): List<UsbSourceFile> = usbScanner.scan()

    /** 本机可读目录（App 外部目录 / Download），系统无文件选择器时的兜底入口。 */
    fun scanLocalFiles(): List<UsbSourceFile> = LocalFileScanner.scan(appContext)

    fun localFileDirHint(): String = LocalFileScanner.importDirHint(appContext)

    /** 本机目录文件导入（与 U 盘/SAF 同一条管线）。 */
    suspend fun importLocalFile(file: UsbSourceFile): ImportReport = withContext(AppDispatchers.Io) {
        val bytes = usbScanner.read(file)
        if (bytes == null) {
            return@withContext record(
                ImportReport.failure(ImportOrigin.FILE, "E_IO", "无法读取文件：${file.name}"),
            )
        }
        record(
            pipeline.importBytes(
                bytes,
                ImportOptions(origin = ImportOrigin.FILE, filename = file.name),
            ),
        )
    }

    // ---------------------------------------------------------------- 内部

    private fun record(report: ImportReport): ImportReport {
        TvLog.i("ImportService", report.logLine())
        _lastReport.value = report
        if (report.ok) {
            val endpoint = report.accepted.firstOrNull()?.endpoint
            if (!endpoint.isNullOrBlank()) historyStore.remember(endpoint)
        }
        return report
    }

    companion object {
        private const val TAG = "ImportService"

        @Volatile
        private var instance: ImportService? = null

        fun get(context: Context): ImportService {
            instance?.let { return it }
            return synchronized(this) {
                instance ?: ImportService(context.applicationContext).also { instance = it }
            }
        }
    }
}
