package com.xingmu.tv.ui.source

import android.app.Activity
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.utf16CodePoint
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.xingmu.tv.core.Result
import com.xingmu.tv.import.ImportInputSpec
import com.xingmu.tv.import.ImportReport
import com.xingmu.tv.import.ImportService
import com.xingmu.tv.import.LanImportState
import com.xingmu.tv.import.SafImport
import com.xingmu.tv.import.UsbSourceFile
import com.xingmu.tv.ui.LocalTvColors
import com.xingmu.tv.ui.LocalTvDimens
import com.xingmu.tv.ui.components.TvButton
import com.xingmu.tv.ui.components.TvLabel
import com.xingmu.tv.ui.components.TvLoading
import com.xingmu.tv.ui.components.isSelectKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 源导入的五个入口（对应 SOURCE_IMPORT_SPEC 的 P1–P4 + 本地文件）。 */
enum class ImportPanel(val title: String, val buttonLabel: String, val debugKey: String) {
    QR("扫码导入（手机）", "二维码", "qr"),
    PAIR("配对码", "配对码", "pair"),
    FILE("本地文件导入", "本地文件", "file"),
    USB("U 盘导入", "U 盘", "usb"),
    KEYBOARD("遥控器键盘输入", "遥控器键盘", "keyboard");

    companion object {
        fun fromDebugKey(key: String?): ImportPanel? =
            values().firstOrNull { it.debugKey.equals(key, ignoreCase = true) }
    }
}

/**
 * 导入面板（全屏子页，父页 [SourceManageScreen] 在 panel != null 时渲染本组件）。
 *
 * 为什么不用弹窗：TV 的 D-pad 焦点不会被 Dialog 之外的内容抢走，全屏子页最稳；
 * 且每个面板退出时能可靠地 `stopLanImport()`（8088 是攻击面，不能常驻）。
 */
@Composable
fun ImportPanelScreen(
    panel: ImportPanel,
    service: ImportService,
    onClose: () -> Unit,
    onImported: (ImportReport) -> Unit,
) {
    val colors = LocalTvColors.current
    val dimens = LocalTvDimens.current
    val backFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { backFocus.requestFocus() } }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TvButton("‹ 返回源列表", onClick = onClose, modifier = Modifier.focusRequester(backFocus))
            Spacer(Modifier.width(12.dp))
            TvLabel(panel.title, dimens.rowTitle, colors.text, weight = FontWeight.Bold)
        }
        Spacer(Modifier.height(10.dp))
        Box(Modifier.weight(1f)) {
            when (panel) {
                ImportPanel.QR -> QrImportPanel(service, onImported)
                ImportPanel.PAIR -> PairCodePanel(service, onImported)
                ImportPanel.FILE -> FileImportPanel(service, onImported)
                ImportPanel.USB -> UsbImportPanel(service, onImported)
                ImportPanel.KEYBOARD -> KeyboardImportPanel(service, onImported)
            }
        }
    }
}

// ---------------------------------------------------------------- 二维码

@Composable
private fun QrImportPanel(service: ImportService, onImported: (ImportReport) -> Unit) {
    val colors = LocalTvColors.current
    val dimens = LocalTvDimens.current
    val state by service.lanState.collectAsState()
    val report by service.lastReport.collectAsState()
    val baseline = remember { service.lastReport.value }
    var handled by remember { mutableStateOf<ImportReport?>(null) }
    var now by remember { mutableStateOf(System.currentTimeMillis()) }

    LaunchedEffect(Unit) {
        if (service.lanState.value == null) service.startLanImport()
    }
    DisposableEffect(Unit) {
        onDispose { service.stopLanImport() }
    }
    LanServiceLifecycleEffect(service)
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(1_000)
        }
    }
    LaunchedEffect(report) {
        val r = report
        if (r != null && r !== baseline && r !== handled) {
            handled = r
            if (r.ok) onImported(r)
        }
    }

    val session = state
    val remainingSec = session?.let { ((it.expiresAt - now) / 1000).coerceAtLeast(0) } ?: 0
    val running = service.isLanRunning()
    val paired = service.isLanPaired()

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        if (session == null) {
            TvLabel("正在启动局域网导入服务…", dimens.body, colors.textMuted)
            Spacer(Modifier.height(8.dp))
            TvButton("重新生成二维码", primary = true, onClick = { service.restartLanImport() })
        } else {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                QrImage(session)
                Spacer(Modifier.width(20.dp))
                Column(Modifier.weight(1f)) {
                    TvLabel("手机连同一个 WiFi，扫码打开导入页", dimens.body, colors.textSecondary, maxLines = 2)
                    TvLabel("也可以手机浏览器打开：${session.serviceUrl}", dimens.body, colors.text, maxLines = 2)
                    Spacer(Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TvLabel("配对码", dimens.body, colors.textMuted)
                        Spacer(Modifier.width(10.dp))
                        TvLabel(
                            session.pairingCode,
                            dimens.rowTitle,
                            colors.accent,
                            weight = FontWeight.Bold,
                            letterSpacing = 4.sp,
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    TvLabel(
                        when {
                            !running && report?.ok == true -> "导入成功，服务已关闭；返回源列表即可看到新源"
                            !running -> "服务已停止（${session.serviceUrl} 不再可访问）"
                            remainingSec <= 0 -> "二维码已过期，请点「重新生成」"
                            else -> "有效期剩余 ${formatRemain(remainingSec)}" +
                                if (paired) " · 手机已配对 ✓" else " · 等待手机配对…"
                        },
                        dimens.caption,
                        if (remainingSec <= 0 && running) colors.danger else colors.textMuted,
                        maxLines = 2,
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TvButton("重新生成", primary = true, onClick = {
                    service.restartLanImport()
                    handled = null
                })
                TvButton("停止服务", enabled = running, onClick = { service.stopLanImport() })
            }
        }
        Spacer(Modifier.height(10.dp))
        ImportReportView(report)
        Spacer(Modifier.height(8.dp))
        TvLabel(
            "安全：服务只在导入页打开，离开即关；导入码 5 分钟有效且成功导入一次后失效。",
            dimens.caption,
            colors.textMuted,
            maxLines = 2,
        )
    }
}

@Composable
private fun QrImage(session: LanImportState) {
    val colors = LocalTvColors.current
    val bitmap = session.qrBitmap
    Box(
        modifier = Modifier
            .size(232.dp)
            .background(colors.surfaceHigh, RoundedCornerShape(8.dp))
            .border(1.dp, colors.divider, RoundedCornerShape(8.dp))
            .padding(8.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = "局域网导入二维码",
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            TvLabel("二维码生成失败\n请改用配对码", LocalTvDimens.current.body, colors.textMuted, maxLines = 2)
        }
    }
}

// ---------------------------------------------------------------- 配对码

@Composable
private fun PairCodePanel(service: ImportService, onImported: (ImportReport) -> Unit) {
    val colors = LocalTvColors.current
    val dimens = LocalTvDimens.current
    val state by service.lanState.collectAsState()
    val report by service.lastReport.collectAsState()
    val baseline = remember { service.lastReport.value }
    var handled by remember { mutableStateOf<ImportReport?>(null) }
    var input by remember { mutableStateOf("") }
    var verifyText by remember { mutableStateOf<String?>(null) }
    var verifyOk by remember { mutableStateOf(false) }
    var now by remember { mutableStateOf(System.currentTimeMillis()) }

    LaunchedEffect(Unit) {
        if (service.lanState.value == null) service.startLanImport()
    }
    DisposableEffect(Unit) {
        onDispose { service.stopLanImport() }
    }
    LanServiceLifecycleEffect(service)
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(1_000)
        }
    }
    LaunchedEffect(report) {
        val r = report
        if (r != null && r !== baseline && r !== handled) {
            handled = r
            if (r.ok) onImported(r)
        }
    }

    val session = state
    val remain = session?.let { ((it.expiresAt - now) / 1000).coerceAtLeast(0) } ?: 0

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        if (session == null) {
            TvLabel("正在启动局域网导入服务…", dimens.body, colors.textMuted)
            Spacer(Modifier.height(8.dp))
            TvButton("重新生成配对码", primary = true, onClick = { service.restartLanImport() })
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TvLabel("电视配对码", dimens.body, colors.textMuted)
                Spacer(Modifier.width(14.dp))
                TvLabel(session.pairingCode, dimens.heroTitle, colors.accent, weight = FontWeight.Bold, letterSpacing = 8.sp)
                Spacer(Modifier.width(18.dp))
                TvLabel("有效期剩余 ${formatRemain(remain)}", dimens.caption, colors.textMuted)
            }
            Spacer(Modifier.height(6.dp))
            TvLabel(
                "手机浏览器打开 ${session.serviceUrl} → 输入上面的 6 位码即可配对并提交源。",
                dimens.body,
                colors.textSecondary,
                maxLines = 2,
            )
            Spacer(Modifier.height(6.dp))
            TvLabel(
                if (service.isLanPaired()) "状态：手机已配对 ✓，等待手机提交源…" else "状态：等待手机输入配对码…",
                dimens.caption,
                if (service.isLanPaired()) colors.accent else colors.textMuted,
            )
            Spacer(Modifier.height(10.dp))
            TvLabel("核对配对码（可选）：输入后可验证手机是否输对", dimens.caption, colors.textMuted)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                repeat(6) { index ->
                    val ch = input.getOrNull(index)?.toString() ?: "_"
                    TvLabel(
                        ch,
                        dimens.rowTitle,
                        if (index < input.length) colors.text else colors.textMuted,
                        modifier = Modifier.padding(horizontal = 6.dp),
                        weight = FontWeight.Bold,
                    )
                }
                Spacer(Modifier.width(12.dp))
                TvButton("⌫", onClick = { if (input.isNotEmpty()) input = input.dropLast(1) }, compact = true)
                Spacer(Modifier.width(8.dp))
                TvButton("校验", primary = true, onClick = {
                    when (val result = service.verifyPairingCode(input)) {
                        is Result.Ok -> {
                            verifyOk = true
                            verifyText = "配对码正确"
                            input = ""
                        }
                        is Result.Err -> {
                            verifyOk = false
                            verifyText = result.error.display()
                            input = ""
                        }
                    }
                })
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                (1..5).forEach { d ->
                    TvButton(d.toString(), onClick = { if (input.length < 6) input += d }, compact = true)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                (6..9).forEach { d ->
                    TvButton(d.toString(), onClick = { if (input.length < 6) input += d }, compact = true)
                }
                TvButton("0", onClick = { if (input.length < 6) input += "0" }, compact = true)
            }
            if (verifyText != null) {
                Spacer(Modifier.height(8.dp))
                TvLabel(verifyText.orEmpty(), dimens.body, if (verifyOk) colors.accent else colors.danger, maxLines = 2)
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TvButton("重新生成", primary = true, onClick = {
                    service.restartLanImport()
                    handled = null
                    verifyText = null
                })
                TvButton("停止服务", enabled = service.isLanRunning(), onClick = { service.stopLanImport() })
            }
        }
        Spacer(Modifier.height(10.dp))
        ImportReportView(report)
    }
}

// ---------------------------------------------------------------- 本地文件

@Composable
private fun FileImportPanel(service: ImportService, onImported: (ImportReport) -> Unit) {
    val context = LocalContext.current
    val colors = LocalTvColors.current
    val dimens = LocalTvDimens.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var report by remember { mutableStateOf<ImportReport?>(null) }
    var files by remember { mutableStateOf<List<UsbSourceFile>>(emptyList()) }
    var scanning by remember { mutableStateOf(true) }
    var scanVersion by remember { mutableIntStateOf(0) }
    var fileMessage by remember { mutableStateOf<String?>(null) }
    val fileFocus = remember { FocusRequester() }
    val launchableIntents = remember(context) { SafImport.resolveLaunchableIntents(context) }
    val hasPicker = launchableIntents.isNotEmpty()
    val dirHint = remember(context) { service.localFileDirHint() }

    LaunchedEffect(Unit) { runCatching { fileFocus.requestFocus() } }
    LaunchedEffect(scanVersion) {
        scanning = true
        files = withContext(Dispatchers.IO) { service.scanLocalFiles() }
        scanning = false
    }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { activityResult ->
        if (activityResult.resultCode != Activity.RESULT_OK) return@rememberLauncherForActivityResult
        val uri = activityResult.data?.data ?: return@rememberLauncherForActivityResult
        scope.launch {
            busy = true
            val result = service.importFromUri(uri)
            report = result
            busy = false
            if (result.ok) onImported(result)
        }
    }

    fun openSystemPicker() {
        if (busy) return
        for (intent in launchableIntents) {
            if (runCatching { launcher.launch(intent) }.isSuccess) return
        }
        report = ImportReport.failure(
            com.xingmu.tv.import.ImportOrigin.FILE,
            "E_IO",
            "系统没有可用的文件选择器，请用下面「扫描本机目录」",
        )
    }

    fun importLocal(targets: List<UsbSourceFile>) {
        if (busy || targets.isEmpty()) return
        scope.launch {
            busy = true
            var total = 0
            var failed = 0
            var last: ImportReport? = null
            for (target in targets) {
                val r = service.importLocalFile(target)
                last = r
                if (r.ok) total += r.accepted.size else failed++
            }
            busy = false
            report = last
            fileMessage = buildString {
                append("已导入 ").append(total).append(" 个源")
                if (failed > 0) append("，").append(failed).append(" 个文件失败")
            }
            if (total > 0 && last != null) onImported(last)
            scanVersion++
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        TvLabel("导入 .json / .m3u / .txt 文件（最大 8MB），走同一套 L0–L4 校验", dimens.body, colors.textSecondary, maxLines = 2)
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TvButton(
                "系统文件选择器",
                primary = true,
                enabled = hasPicker && !busy,
                modifier = if (hasPicker) Modifier.focusRequester(fileFocus) else Modifier,
                onClick = { openSystemPicker() },
            )
            TvButton(
                "扫描本机目录",
                enabled = !scanning && !busy,
                modifier = if (!hasPicker) Modifier.focusRequester(fileFocus) else Modifier,
                onClick = { scanVersion++ },
            )
            TvButton("清除结果", enabled = report != null && !busy, onClick = { report = null })
        }
        if (!hasPicker) {
            Spacer(Modifier.height(8.dp))
            TvLabel(
                "本机没有系统文件选择器（电视盒子常见）。把文件放到：$dirHint 后点「扫描本机目录」。",
                dimens.caption,
                colors.textMuted,
                maxLines = 3,
            )
        }
        Spacer(Modifier.height(10.dp))
        if (busy) {
            TvLoading("正在校验并导入…")
        } else {
            ImportReportView(report)
            if (fileMessage != null) {
                Spacer(Modifier.height(6.dp))
                TvLabel(fileMessage.orEmpty(), dimens.body, colors.accent, maxLines = 2)
            }
            Spacer(Modifier.height(8.dp))
            when {
                scanning -> TvLoading("扫描本机目录…")
                files.isEmpty() -> TvLabel("本机目录未发现源文件（支持 .json/.m3u/.txt，≤8MB）", dimens.caption, colors.textMuted, maxLines = 2)
                else -> {
                    TvLabel("本机目录发现 ${files.size} 个源文件", dimens.body, colors.textSecondary, maxLines = 1)
                    files.forEach { file ->
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                TvLabel(file.name, dimens.body, colors.text, maxLines = 1)
                                TvLabel("${file.path} · ${file.sizeBytes / 1024} KB", dimens.caption, colors.textMuted, maxLines = 1)
                            }
                            TvButton("导入", enabled = !busy, onClick = { importLocal(listOf(file)) })
                        }
                    }
                }
            }
            if (report == null && fileMessage == null) {
                Spacer(Modifier.height(6.dp))
                TvLabel("支持：tvapp.source.v1.1 JSON（单源/合集）、m3u 直播列表、URL 列表文本。", dimens.caption, colors.textMuted, maxLines = 2)
            }
        }
    }
}

// ---------------------------------------------------------------- U 盘

@Composable
private fun UsbImportPanel(service: ImportService, onImported: (ImportReport) -> Unit) {
    val colors = LocalTvColors.current
    val dimens = LocalTvDimens.current
    val scope = rememberCoroutineScope()
    var scanning by remember { mutableStateOf(true) }
    var scannedOnce by remember { mutableStateOf(false) }
    var files by remember { mutableStateOf<List<UsbSourceFile>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    var report by remember { mutableStateOf<ImportReport?>(null) }
    var fileMessage by remember { mutableStateOf<String?>(null) }
    var scanVersion by remember { mutableIntStateOf(0) }

    LaunchedEffect(scanVersion) {
        scanning = true
        files = withContext(Dispatchers.IO) { service.scanUsb() }
        scanning = false
        scannedOnce = true
    }

    fun importFiles(targets: List<UsbSourceFile>) {
        if (busy || targets.isEmpty()) return
        scope.launch {
            busy = true
            var acceptedTotal = 0
            var failed = 0
            var last: ImportReport? = null
            for (file in targets) {
                val r = service.importUsbFile(file)
                last = r
                if (r.ok) acceptedTotal += r.accepted.size else failed++
            }
            busy = false
            report = last
            fileMessage = buildString {
                append("已导入 ").append(acceptedTotal).append(" 个源")
                if (failed > 0) append("，").append(failed).append(" 个文件失败")
            }
            if (acceptedTotal > 0 && last != null) onImported(last)
            scanVersion++
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TvLabel(
                when {
                    scanning -> "正在扫描 U 盘…"
                    files.isEmpty() -> "未发现 U 盘源文件（插入 U 盘后点「重新扫描」）"
                    else -> "发现 ${files.size} 个源文件，导入？"
                },
                dimens.body,
                colors.textSecondary,
                maxLines = 2,
            )
            Spacer(Modifier.width(12.dp))
            TvButton("重新扫描", enabled = !scanning && !busy, onClick = { scanVersion++ })
            Spacer(Modifier.width(8.dp))
            TvButton("全部导入", primary = true, enabled = files.isNotEmpty() && !busy, onClick = {
                importFiles(files)
            })
        }
        Spacer(Modifier.height(8.dp))
        if (scanning) {
            TvLoading("扫描可移除存储…")
        } else {
            files.forEach { file ->
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        TvLabel(file.name, dimens.body, colors.text, maxLines = 1)
                        TvLabel(
                            "${file.path} · ${file.sizeBytes / 1024} KB",
                            dimens.caption,
                            colors.textMuted,
                            maxLines = 1,
                        )
                    }
                    TvButton("导入", enabled = !busy, onClick = { importFiles(listOf(file)) })
                }
            }
        }
        if (fileMessage != null) {
            Spacer(Modifier.height(8.dp))
            TvLabel(fileMessage.orEmpty(), dimens.body, colors.accent, maxLines = 2)
        }
        Spacer(Modifier.height(8.dp))
        if (busy) TvLoading("正在校验并导入…") else ImportReportView(report)
        if (scannedOnce && files.isEmpty()) {
            TvLabel(
                "U 盘权限受限时请在系统设置授权，或改用「本地文件」入口。",
                dimens.caption,
                colors.textMuted,
                maxLines = 2,
            )
        }
    }
}

// ---------------------------------------------------------------- 遥控器键盘

@Composable
private fun KeyboardImportPanel(service: ImportService, onImported: (ImportReport) -> Unit) {
    val colors = LocalTvColors.current
    val dimens = LocalTvDimens.current
    val scope = rememberCoroutineScope()
    val focusRequester = remember { FocusRequester() }
    var input by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var report by remember { mutableStateOf<ImportReport?>(null) }
    var inputFocused by remember { mutableStateOf(false) }
    var historyVersion by remember { mutableIntStateOf(0) }

    val history = remember { service.history() }
    val recent = remember(historyVersion) { history.recent(6) }
    val candidates = remember(input, historyVersion) { ImportInputSpec.completions(input, history.recent(10)) }

    fun submit() {
        if (busy) return
        val url = ImportInputSpec.normalize(input)
        if (url.isBlank() || !ImportInputSpec.isCompleteUrl(url)) {
            report = ImportReport.failure(
                com.xingmu.tv.import.ImportOrigin.URL,
                com.xingmu.tv.import.ImportCodes.URL,
                "请输入完整地址（以 http:// 或 https:// 开头）",
            )
            return
        }
        scope.launch {
            busy = true
            val r = service.importUrl(url)
            report = r
            busy = false
            historyVersion++
            if (r.ok) onImported(r)
        }
    }

    LaunchedEffect(Unit) {
        runCatching { focusRequester.requestFocus() }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Box(
            Modifier
                .fillMaxWidth()
                .focusRequester(focusRequester)
                .onFocusChanged { inputFocused = it.isFocused }
                .focusable()
                .background(colors.surfaceHigh, RoundedCornerShape(6.dp))
                .border(
                    2.dp,
                    if (inputFocused) colors.accent else colors.divider,
                    RoundedCornerShape(6.dp),
                )
                .padding(horizontal = 12.dp, vertical = 10.dp)
                .onPreviewKeyEvent { event ->
                    if (!inputFocused || event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when {
                        event.key == Key.Backspace -> {
                            if (input.isNotEmpty()) input = input.dropLast(1)
                            true
                        }
                        isSelectKey(event.key) -> {
                            if (input.isNotBlank()) submit()
                            true
                        }
                        else -> {
                            val code = event.utf16CodePoint
                            if (code != 0) {
                                val ch = code.toChar()
                                if (ch in ImportInputSpec.ALLOWED_CHARS) {
                                    if (input.length < 512) input += ch
                                    true
                                } else {
                                    false
                                }
                            } else {
                                false
                            }
                        }
                    }
                },
        ) {
            TvLabel(
                if (input.isEmpty()) "点此区域后：外接键盘可直接打字；纯遥控器请用下方分组键盘" else input,
                dimens.body,
                if (input.isEmpty()) colors.textMuted else colors.text,
                maxLines = 3,
            )
        }
        Spacer(Modifier.height(8.dp))

        if (candidates.isNotEmpty()) {
            TvLabel("候选/历史（选中即填入）", dimens.caption, colors.textMuted)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                candidates.take(3).forEach { candidate ->
                    TvButton(candidate.take(42), compact = true, onClick = { input = candidate })
                }
            }
        }
        if (recent.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                recent.take(3).forEach { url ->
                    TvButton("历史 " + url.removePrefix("https://").take(28), compact = true, onClick = { input = url })
                }
            }
        }

        Spacer(Modifier.height(8.dp))
        ImportInputSpec.KEY_ROWS.forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                row.forEach { ch ->
                    TvButton(ch.toString(), compact = true, onClick = {
                        if (input.length < 512) input += ch
                    })
                }
            }
            Spacer(Modifier.height(4.dp))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TvButton("⌫", compact = true, onClick = { if (input.isNotEmpty()) input = input.dropLast(1) })
            TvButton("清空", compact = true, onClick = { input = "" })
            TvButton("补全", compact = true, onClick = {
                ImportInputSpec.completions(input, history.recent(10)).firstOrNull()?.let { input = it }
            })
            TvButton("完成导入", primary = true, enabled = !busy, onClick = { submit() })
        }
        Spacer(Modifier.height(8.dp))
        if (busy) TvLoading("正在校验并导入…") else ImportReportView(report)
        Spacer(Modifier.height(6.dp))
        TvLabel(
            "提示：域名可只输 macapi1 / bfzy 等前缀，点「补全」自动变成完整 API 地址。",
            dimens.caption,
            colors.textMuted,
            maxLines = 2,
        )
    }
}

// ---------------------------------------------------------------- 公共

/**
 * 导入页在后台时立即关闭 8088（SOURCE_IMPORT_SPEC §2.5：离开导入页即关，减少攻击面）。
 * 回到前台重新生成 token（旧二维码失效，UI 通过 lanState 自动刷新）。
 */
@Composable
private fun LanServiceLifecycleEffect(service: ImportService) {
    val context = LocalContext.current
    val activity = context as? ComponentActivity
    DisposableEffect(activity, service) {
        if (activity == null) return@DisposableEffect onDispose { }
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> service.stopLanImport()
                Lifecycle.Event.ON_START -> if (service.lanState.value == null) service.startLanImport()
                else -> Unit
            }
        }
        activity.lifecycle.addObserver(observer)
        onDispose { activity.lifecycle.removeObserver(observer) }
    }
}

@Composable
private fun ImportReportView(report: ImportReport?) {
    if (report == null) return
    val colors = LocalTvColors.current
    val dimens = LocalTvDimens.current
    val ok = report.ok
    TvLabel(
        if (ok) "✓ ${report.summaryText()}" else "✗ ${report.firstErrorText() ?: report.summaryText()}",
        dimens.body,
        if (ok) colors.accent else colors.danger,
        weight = FontWeight.SemiBold,
        maxLines = 3,
    )
    report.rejected.take(4).forEach { issue ->
        TvLabel("[${issue.code}] ${issue.message}", dimens.caption, colors.textMuted, maxLines = 2)
    }
    report.warnings.take(3).forEach { warning ->
        TvLabel("提示 [${warning.code}] ${warning.message}", dimens.caption, colors.textMuted, maxLines = 2)
    }
    if (ok) {
        TvLabel(
            "已写入源列表（${report.persisted} 条变更），可返回查看。",
            dimens.caption,
            colors.textMuted,
            maxLines = 1,
        )
    }
}

private fun formatRemain(seconds: Long): String {
    val m = seconds / 60
    val s = seconds % 60
    return "%d:%02d".format(m, s)
}

/** 调试入口：Activity intent 带 `importPanel=qr|pair|file|usb|keyboard` 时直接打开对应面板（QA 截图用）。 */
@Composable
internal fun rememberDebugImportPanel(): ImportPanel? {
    val context = LocalContext.current
    return remember(context) {
        val activity = context as? Activity ?: return@remember null
        ImportPanel.fromDebugKey(activity.intent?.getStringExtra("importPanel"))
    }
}
