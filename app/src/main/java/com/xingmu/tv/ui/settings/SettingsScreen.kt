package com.xingmu.tv.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xingmu.tv.core.Result
import com.xingmu.tv.data.AndroidSeedDatabase
import com.xingmu.tv.data.DetailSeedPrefs
import com.xingmu.tv.data.DetailSeedStore
import com.xingmu.tv.data.DetailSeedSync
import com.xingmu.tv.data.SeedSyncState
import com.xingmu.tv.data.SourceAdminDataSource
import com.xingmu.tv.data.casual.CasualRepository
import com.xingmu.tv.source.SourceRepository
import com.xingmu.tv.ui.FontTier
import com.xingmu.tv.ui.DpadDownSink
import com.xingmu.tv.ui.LocalTvColors
import com.xingmu.tv.ui.LocalTvDimens
import com.xingmu.tv.ui.components.TvButton
import com.xingmu.tv.ui.components.TvChip
import com.xingmu.tv.ui.components.TvLabel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 设置页（t14 B）：显示 / 播放 / 语音 / 关于。
 * 每次改动立即通过 onChange 写 SharedPreferences 并触发 AppRoot 重组；重启后由 SettingsStore 恢复。
 */
@Composable
fun SettingsScreen(
    values: AppSettingsValues,
    onChange: (AppSettingsValues) -> Unit,
    repo: SourceRepository,
    casualRepo: CasualRepository,
    onBack: () -> Unit,
) {
    val colors = LocalTvColors.current
    val dimens = LocalTvDimens.current
    val context = LocalContext.current
    var dialog by remember { mutableStateOf<EditField?>(null) }
    var sourceCount by remember { mutableIntStateOf(-1) }
    var channelCount by remember { mutableIntStateOf(-1) }

    LaunchedEffect(repo) {
        val all = (repo as? SourceAdminDataSource)?.listAllSources() ?: repo.listSources()
        sourceCount = all.getOrNull()?.size ?: 0
        channelCount = repo.listChannels().let { if (it is Result.Ok) it.value.size else 0 }
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TvButton("‹ 返回", onClick = onBack)
            Spacer(Modifier.width(12.dp))
            TvLabel("设置", dimens.rowTitle, colors.text, weight = FontWeight.Bold)
            Spacer(Modifier.width(1.dp).weight(1f))
            TvLabel("改动立即生效并持久化", dimens.caption, colors.textMuted)
        }

        Section("显示") {
            RowSetting("大字号模式") {
                FontTier.entries.forEach { tier ->
                    TvChip(
                        text = tier.label,
                        selected = values.fontTier == tier,
                        onClick = { onChange(values.copy(fontTier = tier)) },
                    )
                    Spacer(Modifier.width(6.dp))
                }
            }
            RowSetting("过扫描补偿") {
                listOf(0f to "0%", 2.5f to "2.5%", 5f to "5%").forEach { (percent, label) ->
                    TvChip(
                        text = label,
                        selected = kotlin.math.abs(values.overscanPercent - percent) < 0.01f,
                        onClick = { onChange(values.copy(overscanPercent = percent)) },
                    )
                    Spacer(Modifier.width(6.dp))
                }
            }
            RowSetting("C 保命模式") {
                TvChip("关", selected = !values.forceLite, onClick = { onChange(values.copy(forceLite = false)) })
                Spacer(Modifier.width(6.dp))
                TvChip("开", selected = values.forceLite, onClick = { onChange(values.copy(forceLite = true)) })
            }
        }

        Section("预置数据") {
            // t27：预置热门详情包（378KB），下载后详情冷开从 2.9–7.8s 降到本地秒开
            val seedSync = remember { DetailSeedSync.get(context) }
            val seedState by seedSync.state.collectAsState()
            var seedSummary by remember { mutableStateOf("读取中…") }
            LaunchedEffect(seedState) {
                seedSummary = withContext(Dispatchers.IO) {
                    runCatching {
                        val stats = DetailSeedStore(AndroidSeedDatabase(context)).stats()
                        if (stats.count == 0) {
                            "尚未导入（首次连 Wi-Fi 自动下载 378KB）"
                        } else {
                            "${stats.count} 部作品 · 版本 ${stats.version ?: "-"} · 详情本地秒开"
                        }
                    }.getOrDefault("状态读取失败")
                }
            }
            RowSetting("使用预置详情包") {
                TvChip(
                    "关",
                    selected = !values.seedDetails,
                    onClick = {
                        onChange(values.copy(seedDetails = false))
                        DetailSeedPrefs(context).setEnabled(false)
                    },
                )
                Spacer(Modifier.width(6.dp))
                TvChip(
                    "开",
                    selected = values.seedDetails,
                    onClick = {
                        onChange(values.copy(seedDetails = true))
                        DetailSeedPrefs(context).setEnabled(true)
                        seedSync.startIfNeeded()
                    },
                )
                Spacer(Modifier.width(12.dp))
                TvLabel(seedStateText(seedState), dimens.caption, colors.textMuted)
            }
            RowSetting("预置包状态") {
                TvLabel(seedSummary, dimens.caption, colors.textMuted)
            }
        }

        Section("播放") {
            RowSetting("直播 4K 优先") {
                TvChip("关", selected = !values.prefer4K, onClick = { onChange(values.copy(prefer4K = false)) })
                Spacer(Modifier.width(6.dp))
                TvChip("开", selected = values.prefer4K, onClick = { onChange(values.copy(prefer4K = true)) })
            }
            RowSetting("硬解优先") {
                TvChip("关", selected = !values.hardwareFirst, onClick = { onChange(values.copy(hardwareFirst = false)) })
                Spacer(Modifier.width(6.dp))
                TvChip("开", selected = values.hardwareFirst, onClick = { onChange(values.copy(hardwareFirst = true)) })
            }
            RowSetting("画质上限") {
                listOf(0 to "自动", 1080 to "1080p", 720 to "720p", 480 to "480p").forEach { (cap, label) ->
                    TvChip(
                        text = label,
                        selected = values.qualityCap == cap,
                        onClick = { onChange(values.copy(qualityCap = cap)) },
                    )
                    Spacer(Modifier.width(6.dp))
                }
            }
            RowSetting("音画校准（ms）") {
                TvButton("-50", onClick = { onChange(values.copy(audioDelayMs = (values.audioDelayMs - 50).coerceIn(-500, 500))) })
                Spacer(Modifier.width(6.dp))
                TvLabel("${values.audioDelayMs}", dimens.body, colors.accent, weight = FontWeight.Bold)
                Spacer(Modifier.width(6.dp))
                TvButton("+50", onClick = { onChange(values.copy(audioDelayMs = (values.audioDelayMs + 50).coerceIn(-500, 500))) })
                Spacer(Modifier.width(6.dp))
                TvButton("归零", onClick = { onChange(values.copy(audioDelayMs = 0)) })
            }
            RowSetting("字幕字号") {
                listOf(0 to "小", 1 to "标准", 2 to "大").forEach { (size, label) ->
                    TvChip(
                        text = label,
                        selected = values.subtitleSize == size,
                        onClick = { onChange(values.copy(subtitleSize = size)) },
                    )
                    Spacer(Modifier.width(6.dp))
                }
            }
            RowSetting("字幕描边") {
                TvChip("关", selected = !values.subtitleOutline, onClick = { onChange(values.copy(subtitleOutline = false)) })
                Spacer(Modifier.width(6.dp))
                TvChip("开", selected = values.subtitleOutline, onClick = { onChange(values.copy(subtitleOutline = true)) })
            }
        }

        Section("语音搜索（P1 自建 ASR）") {
            TvLabel(
                "无系统语音识别时使用；OpenAI 兼容 /audio/transcriptions（16k/16bit 单声道 WAV）。",
                dimens.caption,
                colors.textMuted,
                maxLines = 2,
            )
            RowSetting("Endpoint") {
                TvLabel(values.asrEndpoint.ifBlank { "未配置" }, dimens.caption, colors.textSecondary, maxLines = 1)
                Spacer(Modifier.width(8.dp))
                TvButton("编辑", onClick = { dialog = EditField.ASR_ENDPOINT })
            }
            RowSetting("API Key") {
                TvLabel(if (values.asrKey.isBlank()) "未配置" else "已配置（${values.asrKey.length} 字符）", dimens.caption, colors.textSecondary)
                Spacer(Modifier.width(8.dp))
                TvButton("编辑", onClick = { dialog = EditField.ASR_KEY })
            }
            RowSetting("模型") {
                TvLabel(values.asrModel, dimens.caption, colors.textSecondary)
                Spacer(Modifier.width(8.dp))
                TvButton("编辑", onClick = { dialog = EditField.ASR_MODEL })
            }
            TvLabel(
                if (values.asrConfigured) "已启用：搜索页显示语音按钮，按住 OK 录音、松手识别。" else "未配置：搜索页仅提示「可用首拼搜索」。",
                dimens.caption,
                if (values.asrConfigured) colors.accent else colors.textMuted,
                maxLines = 2,
            )
        }

        Section("关于") {
            val version = remember(context) {
                runCatching {
                    context.packageManager.getPackageInfo(context.packageName, 0).versionName
                }.getOrNull() ?: "0.1.0"
            }
            TvLabel("星幕 · v$version", dimens.body, colors.text, weight = FontWeight.SemiBold)
            TvLabel(
                "内置源 ${if (sourceCount >= 0) sourceCount else "…"} 个 · 直播频道 ${if (channelCount >= 0) channelCount else "…"} 个",
                dimens.caption,
                colors.textSecondary,
                maxLines = 1,
            )
            TvLabel(
                "协议与边界：内容来自公开接口（MacCMS / Radio Browser / B站公开榜单），仅个人观看收听；不下载、不内置登录、不接抖音/快手；源与电台可用性由第三方决定。",
                dimens.caption,
                colors.textMuted,
                maxLines = 4,
            )
        }

        Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TvButton("恢复默认（显示+播放）", onClick = {
                onChange(
                    values.copy(
                        fontTier = FontTier.STANDARD,
                        overscanPercent = 0f,
                        forceLite = false,
                        prefer4K = false,
                        hardwareFirst = true,
                        qualityCap = 0,
                        audioDelayMs = 0,
                        subtitleSize = 1,
                        subtitleOutline = true,
                    ),
                )
            })
        }
        DpadDownSink()
    }

    dialog?.let { field ->
        SettingsTextDialog(
            title = field.title,
            initial = when (field) {
                EditField.ASR_ENDPOINT -> values.asrEndpoint
                EditField.ASR_KEY -> values.asrKey
                EditField.ASR_MODEL -> values.asrModel
            },
            hint = field.hint,
            onCancel = { dialog = null },
            onConfirm = { text ->
                onChange(
                    when (field) {
                        EditField.ASR_ENDPOINT -> values.copy(asrEndpoint = text.trim())
                        EditField.ASR_KEY -> values.copy(asrKey = text.trim())
                        EditField.ASR_MODEL -> values.copy(asrModel = text.trim().ifBlank { "whisper-1" })
                    },
                )
                dialog = null
            },
        )
    }
}

private enum class EditField(val title: String, val hint: String) {
    ASR_ENDPOINT("ASR Endpoint", "https://api.example.com/v1/audio/transcriptions"),
    ASR_KEY("ASR API Key", "sk-…"),
    ASR_MODEL("ASR 模型", "whisper-1"),
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    val colors = LocalTvColors.current
    val dimens = LocalTvDimens.current
    Column(Modifier.fillMaxWidth()) {
        TvLabel(title, dimens.cardTitle, colors.accent, weight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            content()
        }
    }
}

@Composable
private fun RowSetting(label: String, content: @Composable () -> Unit) {
    val colors = LocalTvColors.current
    val dimens = LocalTvDimens.current
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        TvLabel(label, dimens.body, colors.textSecondary, modifier = Modifier.width(180.dp), maxLines = 1)
        Row(verticalAlignment = Alignment.CenterVertically) { content() }
    }
}

@Composable
private fun SettingsTextDialog(
    title: String,
    initial: String,
    hint: String,
    onCancel: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    val colors = LocalTvColors.current
    val dimens = LocalTvDimens.current
    var text by remember { mutableStateOf(initial) }
    val focus = remember { FocusRequester() }
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.9f))
            .padding(48.dp),
    ) {
        Column(Modifier.fillMaxWidth().focusable()) {
            TvLabel(title, dimens.rowTitle, colors.text, weight = FontWeight.Bold)
            Spacer(Modifier.height(10.dp))
            TvLabel(hint, dimens.caption, colors.textMuted, maxLines = 2)
            Spacer(Modifier.height(12.dp))
            BasicTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                textStyle = TextStyle(color = colors.text, fontSize = 22.sp),
                modifier = Modifier
                    .fillMaxWidth()
                    .background(colors.surface)
                    .padding(14.dp)
                    .focusRequester(focus),
            )
            Spacer(Modifier.height(10.dp))
            TvLabel("可用遥控器/系统输入法或 adb 输入；URL 以 https:// 开头", dimens.caption, colors.textMuted)
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                TvButton("保存", onClick = { onConfirm(text) }, primary = true)
                TvButton("取消", onClick = onCancel)
                TvButton("清空", onClick = { text = "" })
            }
        }
    }
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(120)
        runCatching { focus.requestFocus() }
    }
}

/** t27：预置包同步状态 → 设置页文案。 */
private fun seedStateText(state: SeedSyncState): String = when (state) {
    is SeedSyncState.Idle -> "待下载"
    is SeedSyncState.Downloading ->
        if (state.total > 0) "下载中 ${state.read * 100 / state.total}%" else "下载中 ${state.read / 1024}KB"
    is SeedSyncState.Verifying -> "校验中"
    is SeedSyncState.Importing -> "导入中 ${state.count} 条"
    is SeedSyncState.Ready -> "已就绪 ${state.count} 条（导入 ${state.importMs}ms）"
    is SeedSyncState.Failed -> "下载失败，将静默重试"
}
