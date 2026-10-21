package com.tvbox.app.ui.source

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.tvbox.app.core.AppError
import com.tvbox.app.ui.DpadDownSink
import com.tvbox.app.core.Result
import com.tvbox.app.data.SourceConfig
import com.tvbox.app.data.SourceAdminDataSource
import com.tvbox.app.import.ImportService
import com.tvbox.app.source.SourceRepository
import com.tvbox.app.ui.LocalTvColors
import com.tvbox.app.ui.LocalTvDimens
import com.tvbox.app.ui.components.StatusDot
import com.tvbox.app.ui.components.TvButton
import com.tvbox.app.ui.components.TvErrorState
import com.tvbox.app.ui.components.TvLabel
import com.tvbox.app.ui.components.TvLoading
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 播放源管理页：
 *  - 列表：状态灯 / 名称 / 类型 / 优先级 / 延迟 / 地址；
 *  - 操作：测试连通、启用停用、上移下移（改 priority，数值小优先）、删除；
 *  - 添加播放源：二维码 / 配对码 / 本地文件 / U 盘 / 遥控器键盘，五个入口全部走
 *    [com.tvbox.app.import.ImportService] 的同一条 L0–L4 校验管线（实现见 ImportPanel.kt）。
 */
@Composable
fun SourceManageScreen(
    repo: SourceRepository,
    onBack: () -> Unit,
) {
    val colors = LocalTvColors.current
    val dimens = LocalTvDimens.current
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val importService = remember(context) { ImportService.get(context) }
    val debugPanel = rememberDebugImportPanel()

    var panel by remember { mutableStateOf<ImportPanel?>(debugPanel) }
    var sources by remember { mutableStateOf<List<SourceConfig>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<AppError?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    var latencies by remember { mutableStateOf<Map<String, Int>>(emptyMap()) }
    var testingId by remember { mutableStateOf<String?>(null) }
    var channelCount by remember { mutableStateOf<Int?>(null) }

    LaunchedEffect(reload) {
        loading = true
        error = null
        // 冻结接口 listSources() 只返回启用源；管理页走 SourceAdminDataSource（含停用源）。
        val admin = repo as? SourceAdminDataSource
        val result = admin?.listAllSources() ?: repo.listSources()
        when (result) {
            is Result.Ok -> sources = result.value.sortedBy { it.priority }
            is Result.Err -> error = result.error
        }
        if (channelCount == null) {
            channelCount = repo.listChannels().getOrNull()?.size
        }
        loading = false
    }

    LaunchedEffect(message) {
        if (message != null) {
            delay(4_000)
            message = null
        }
    }

    fun runAction(text: String, block: suspend () -> Result<Unit>) {
        scope.launch {
            when (val result = block()) {
                is Result.Ok -> {
                    message = text
                    reload++
                }
                is Result.Err -> message = result.error.display()
            }
        }
    }

    val activePanel = panel
    if (activePanel != null) {
        ImportPanelScreen(
            panel = activePanel,
            service = importService,
            onClose = { panel = null },
            onImported = { report ->
                // 导入成功：立刻刷新列表，并在返回后显示「已导入 N 个源 / M 个频道」
                message = report.summaryText()
                channelCount = null
                reload++
            },
        )
        return
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TvButton("‹ 返回", onClick = onBack)
            Spacer(Modifier.width(12.dp))
            TvLabel("播放源管理", dimens.rowTitle, colors.text, weight = FontWeight.Bold)
            Spacer(Modifier.width(16.dp))
            TvLabel(
                "启用 ${sources.count { it.enabled }} / 共 ${sources.size} · 直播频道 ${channelCount ?: "…"}",
                dimens.caption,
                colors.textMuted,
            )
            Spacer(Modifier.width(1.dp).weight(1f))
            TvButton("刷新", onClick = { reload++ })
        }

        if (message != null) {
            Spacer(Modifier.height(8.dp))
            TvLabel(message.orEmpty(), dimens.body, colors.accent, maxLines = 2)
        }

        Spacer(Modifier.height(12.dp))
        Box(Modifier.weight(1f)) {
            when {
                loading && sources.isEmpty() -> TvLoading("正在读取源配置…")
                error != null && sources.isEmpty() -> TvErrorState(
                    error = error ?: AppError("E_UNKNOWN", "读取源配置失败"),
                    onRetry = { reload++ },
                )
                sources.isEmpty() -> TvLabel("没有可用源", dimens.body, colors.textMuted)
                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(bottom = 12.dp),
                ) {
                    items(sources, key = { it.id }) { source ->
                        SourceRow(
                            source = source,
                            latencyMs = latencies[source.id],
                            testing = testingId == source.id,
                            onTest = {
                                if (testingId != null) return@SourceRow
                                testingId = source.id
                                scope.launch {
                                    when (val result = repo.testSource(source.id)) {
                                        is Result.Ok -> {
                                            latencies = latencies + (source.id to result.value)
                                            message = "${source.name} 可用，延迟 ${result.value}ms"
                                        }
                                        is Result.Err -> message = "${source.name} 不可用：${result.error.display()}"
                                    }
                                    testingId = null
                                }
                            },
                            onToggle = {
                                runAction(if (source.enabled) "已停用 ${source.name}" else "已启用 ${source.name}") {
                                    repo.setEnabled(source.id, !source.enabled)
                                }
                            },
                            onMoveUp = {
                                val index = sources.indexOfFirst { it.id == source.id }
                                if (index > 0) {
                                    val previous = sources[index - 1]
                                    scope.launch {
                                        val priority = if (previous.priority >= source.priority) {
                                            (previous.priority - 1).coerceAtLeast(0)
                                        } else {
                                            previous.priority
                                        }
                                        repo.updatePriority(source.id, priority)
                                        repo.updatePriority(previous.id, source.priority)
                                        message = "${source.name} 已上移"
                                        reload++
                                    }
                                }
                            },
                            onMoveDown = {
                                val index = sources.indexOfFirst { it.id == source.id }
                                if (index in 0 until sources.lastIndex) {
                                    val next = sources[index + 1]
                                    scope.launch {
                                        val priority = if (next.priority <= source.priority) {
                                            next.priority + 1
                                        } else {
                                            next.priority
                                        }
                                        repo.updatePriority(source.id, priority)
                                        repo.updatePriority(next.id, source.priority)
                                        message = "${source.name} 已下移"
                                        reload++
                                    }
                                }
                            },
                            onRemove = {
                                runAction("已删除 ${source.name}（可从内置目录恢复）") {
                                    repo.removeSource(source.id)
                                }
                            },
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(10.dp))
        TvLabel("添加播放源（手机扫码 / 配对码 / 本地文件 / U 盘 / 遥控器键盘）", dimens.cardTitle, colors.textSecondary, weight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ImportPanel.values().forEach { entry ->
                TvButton(entry.buttonLabel, onClick = { panel = entry })
            }
        }
        Spacer(Modifier.height(6.dp))
        TvLabel(
            "说明：导入的源存入本地 SQLite；内置源来自 assets/sources.builtin.json，停用/删除只写用户覆盖层。手机扫码需与电视同一 WiFi。",
            dimens.caption,
            colors.textMuted,
            maxLines = 2,
        )
    }
}

@Composable
private fun SourceRow(
    source: SourceConfig,
    latencyMs: Int?,
    testing: Boolean,
    onTest: () -> Unit,
    onToggle: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onRemove: () -> Unit,
) {
    val colors = LocalTvColors.current
    val dimens = LocalTvDimens.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.surface)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StatusDot(active = source.enabled)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            TvLabel(source.name, dimens.cardTitle, colors.text, weight = FontWeight.Medium, maxLines = 1)
            TvLabel(
                buildString {
                    append(source.kind.name)
                    append(" · 优先级 ").append(source.priority)
                    append(" · ")
                    append(
                        when {
                            latencyMs != null -> "延迟 ${latencyMs}ms"
                            testing -> "测试中…"
                            source.enabled -> "未测速"
                            else -> "已停用"
                        },
                    )
                },
                dimens.caption,
                colors.textMuted,
                maxLines = 1,
            )
            TvLabel(
                source.baseUrl.ifBlank { "（无基址）" },
                dimens.caption,
                colors.textMuted,
                maxLines = 1,
            )
        }
        Spacer(Modifier.width(8.dp))
        TvButton("测试", onClick = onTest, enabled = !testing)
        Spacer(Modifier.width(6.dp))
        TvButton(if (source.enabled) "停用" else "启用", onClick = onToggle)
        Spacer(Modifier.width(6.dp))
        TvButton("↑", onClick = onMoveUp)
        Spacer(Modifier.width(6.dp))
        TvButton("↓", onClick = onMoveDown)
        Spacer(Modifier.width(6.dp))
        TvButton("删除", onClick = onRemove)
    }
}
