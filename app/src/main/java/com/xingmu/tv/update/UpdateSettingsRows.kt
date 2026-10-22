package com.xingmu.tv.update

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.xingmu.tv.ui.LocalTvColors
import com.xingmu.tv.ui.LocalTvDimens
import com.xingmu.tv.ui.components.TvButton
import com.xingmu.tv.ui.components.TvChip
import com.xingmu.tv.ui.components.TvLabel
import com.xingmu.tv.ui.settings.AppSettingsValues
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch

/**
 * 设置页「关于」区的更新相关行（UPDATE_SPEC §4）：
 * 当前版本 / 检查更新 / 自动检查 / 包含测试版 / 上次检查 + 已知限制脚注。
 */
@Composable
fun UpdateAboutRows(
    values: AppSettingsValues,
    onChange: (AppSettingsValues) -> Unit,
    center: UpdateCenter,
) {
    val colors = LocalTvColors.current
    val dimens = LocalTvDimens.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state by center.state.collectAsState()
    var manualText by remember { mutableStateOf<String?>(null) }
    var lastCheckAt by remember { mutableLongStateOf(center.lastCheckAt) }
    LaunchedEffect(state) { lastCheckAt = center.lastCheckAt }

    val checking = state is UpdateState.Checking

    UpdateRow("当前版本") {
        TvLabel(AppVersion.display(context), dimens.caption, colors.textSecondary, maxLines = 1)
    }
    UpdateRow("检查更新") {
        TvButton(
            text = if (checking) "检查中…" else "检查更新",
            enabled = !checking,
            onClick = {
                manualText = null
                scope.launch {
                    val result = center.checkNow(
                        manual = true,
                        includePrerelease = values.includePrereleaseUpdate,
                    )
                    manualText = when (result) {
                        is UpdateCheckResult.Available -> "发现 ${result.candidate.release.tag}"
                        is UpdateCheckResult.UpToDate -> "已是最新"
                        is UpdateCheckResult.Failed -> "检查失败（网络不可用）"
                        is UpdateCheckResult.Skipped -> null
                    }
                }
            },
        )
        Spacer(Modifier.width(10.dp))
        TvLabel(statusText(state, manualText), dimens.caption, colors.textMuted, maxLines = 1)
    }
    UpdateRow("自动检查") {
        TvChip("关", selected = !values.autoCheckUpdate, onClick = { onChange(values.copy(autoCheckUpdate = false)) })
        Spacer(Modifier.width(6.dp))
        TvChip("开", selected = values.autoCheckUpdate, onClick = { onChange(values.copy(autoCheckUpdate = true)) })
        Spacer(Modifier.width(10.dp))
        TvLabel("仅 Wi-Fi，24 小时内最多一次", dimens.caption, colors.textMuted, maxLines = 1)
    }
    UpdateRow("包含测试版") {
        TvChip(
            "关",
            selected = !values.includePrereleaseUpdate,
            onClick = { onChange(values.copy(includePrereleaseUpdate = false)) },
        )
        Spacer(Modifier.width(6.dp))
        TvChip(
            "开",
            selected = values.includePrereleaseUpdate,
            onClick = { onChange(values.copy(includePrereleaseUpdate = true)) },
        )
        Spacer(Modifier.width(10.dp))
        TvLabel("预发布版本默认不提示", dimens.caption, colors.textMuted, maxLines = 1)
    }
    UpdateRow("上次检查") {
        TvLabel(formatCheckTime(lastCheckAt), dimens.caption, colors.textSecondary, maxLines = 1)
    }
    TvLabel(
        "已知限制：更新检查走 GitHub API，国内网络可能访问不了；失败时静默跳过，不影响使用。" +
            "只做「下载 + 调起系统安装器」，不做静默安装与增量更新；需要时也可到 Release 页手动下载。",
        dimens.caption,
        colors.textMuted,
        maxLines = 4,
    )
}

@Composable
private fun UpdateRow(label: String, content: @Composable () -> Unit) {
    val colors = LocalTvColors.current
    val dimens = LocalTvDimens.current
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        TvLabel(label, dimens.body, colors.textSecondary, modifier = Modifier.width(180.dp), maxLines = 1)
        Row(verticalAlignment = Alignment.CenterVertically) { content() }
    }
}

private fun statusText(state: UpdateState, manualText: String?): String = when (state) {
    is UpdateState.Checking -> "检查中…"
    is UpdateState.UpToDate -> "已是最新"
    is UpdateState.Available -> "发现 ${state.candidate.release.tag}"
    is UpdateState.Downloading -> "正在下载 ${state.percent}%"
    is UpdateState.ReadyToInstall -> "安装包已就绪"
    is UpdateState.Failed -> state.message
    is UpdateState.Idle -> manualText ?: "手动检查不受频率限制"
}

private fun formatCheckTime(at: Long): String {
    if (at <= 0L) return "从未"
    val format = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
    return format.format(Date(at))
}
