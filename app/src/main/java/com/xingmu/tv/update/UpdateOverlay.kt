package com.xingmu.tv.update

import androidx.activity.compose.BackHandler
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.xingmu.tv.ui.LocalTvColors
import com.xingmu.tv.ui.LocalTvDimens
import com.xingmu.tv.ui.components.TvButton
import com.xingmu.tv.ui.components.TvLabel
import java.util.Locale
import kotlinx.coroutines.delay

/**
 * 更新弹窗（UPDATE_SPEC §3）：只在 [UpdateState.Available] 及之后出现。
 * 检查失败时状态是 Idle，本函数直接 return —— 结构上保证「访问不了绝不弹窗」。
 */
@Composable
fun UpdateOverlay(center: UpdateCenter) {
    val state by center.state.collectAsState()
    val current = state
    if (current is UpdateState.Idle || current is UpdateState.Checking || current is UpdateState.UpToDate) {
        return
    }

    val colors = LocalTvColors.current
    val dimens = LocalTvDimens.current
    val primaryFocus = remember { FocusRequester() }

    BackHandler(enabled = current is UpdateState.Available) { center.dismiss() }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.86f))
            .focusable(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .fillMaxWidth(0.72f)
                .background(colors.surface)
                .padding(horizontal = 28.dp, vertical = 22.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            when (current) {
                is UpdateState.Available -> AvailableContent(current, center, primaryFocus)
                is UpdateState.Downloading -> DownloadingContent(current, center)
                is UpdateState.ReadyToInstall -> ReadyContent(current, center, primaryFocus)
                is UpdateState.Failed -> FailedContent(current, center, primaryFocus)
                else -> Unit
            }
        }
    }

    LaunchedEffect(current::class) {
        delay(80)
        runCatching { primaryFocus.requestFocus() }
    }
}

@Composable
private fun AvailableContent(
    state: UpdateState.Available,
    center: UpdateCenter,
    primaryFocus: FocusRequester,
) {
    val colors = LocalTvColors.current
    val dimens = LocalTvDimens.current
    val candidate = state.candidate
    val release = candidate.release
    val flavorText = when (candidate.flavor) {
        ApkFlavor.R8 -> "精简包"
        ApkFlavor.STANDARD -> "标准包"
        null -> "未提供安装包"
    }

    TvLabel("发现新版本 ${release.tag}", dimens.rowTitle, colors.text, weight = FontWeight.Bold)
    TvLabel(
        "当前 ${state.currentVersion} → 最新 ${release.version} · $flavorText",
        dimens.caption,
        colors.textSecondary,
        maxLines = 1,
    )
    val notes = ReleaseNotes.plain(release.notes)
    if (notes.isNotBlank()) {
        TvLabel(notes, dimens.body, colors.textSecondary, maxLines = 8)
    }
    if (candidate.pageOnly) {
        TvLabel(
            "该版本没有匹配的 APK 资产，可打开 Release 页面手动下载。",
            dimens.caption,
            colors.danger,
            maxLines = 2,
        )
        TvLabel(release.pageUrl, dimens.caption, colors.textMuted, maxLines = 1)
    } else {
        val size = candidate.asset?.sizeBytes ?: 0L
        TvLabel("安装包 ${formatSize(size)}", dimens.caption, colors.textMuted, maxLines = 1)
    }

    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        if (candidate.pageOnly) {
            TvButton(
                "打开 Release 页面",
                onClick = { center.openReleasePage(release.pageUrl) },
                primary = true,
                modifier = Modifier.focusRequester(primaryFocus),
            )
        } else {
            TvButton(
                "立即更新",
                onClick = { center.startDownload() },
                primary = true,
                modifier = Modifier.focusRequester(primaryFocus),
            )
        }
        TvButton("稍后", onClick = { center.dismiss() })
    }
}

@Composable
private fun DownloadingContent(state: UpdateState.Downloading, center: UpdateCenter) {
    val colors = LocalTvColors.current
    val dimens = LocalTvDimens.current
    TvLabel("正在下载更新包", dimens.rowTitle, colors.text, weight = FontWeight.Bold)
    TvLabel(
        "${state.candidate.release.tag} · ${state.percent}% · ${formatSize(state.bytes)} / ${formatSize(state.total)}",
        dimens.caption,
        colors.textSecondary,
        maxLines = 1,
    )
    val fraction = if (state.total > 0) (state.bytes.toFloat() / state.total).coerceIn(0f, 1f) else 0f
    Box(Modifier.fillMaxWidth().height(10.dp).background(colors.surfaceHigh)) {
        Box(Modifier.fillMaxWidth(fraction).height(10.dp).background(colors.accent))
    }
    TvLabel("下载到本机缓存，完成后会调起系统安装器。", dimens.caption, colors.textMuted, maxLines = 2)
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        TvButton("取消", onClick = { center.cancelDownload() })
    }
}

@Composable
private fun ReadyContent(
    state: UpdateState.ReadyToInstall,
    center: UpdateCenter,
    primaryFocus: FocusRequester,
) {
    val colors = LocalTvColors.current
    val dimens = LocalTvDimens.current
    TvLabel("更新包已下载", dimens.rowTitle, colors.text, weight = FontWeight.Bold)
    TvLabel(
        "${state.candidate.release.tag} · ${formatSize(state.file.length())}",
        dimens.caption,
        colors.textSecondary,
        maxLines = 1,
    )
    if (state.needPermission) {
        TvLabel(
            "请允许「星幕」安装应用后重试（系统设置 → 安装未知应用）。",
            dimens.body,
            colors.danger,
            maxLines = 3,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TvButton(
                "去授权",
                onClick = { center.openInstallPermissionSettings() },
                primary = true,
                modifier = Modifier.focusRequester(primaryFocus),
            )
            TvButton("我已授权，安装", onClick = { center.installDownloaded() })
            TvButton("稍后", onClick = { center.dismiss() })
        }
    } else {
        TvLabel("点击「立即安装」调起系统安装器。", dimens.caption, colors.textMuted, maxLines = 2)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TvButton(
                "立即安装",
                onClick = { center.installDownloaded() },
                primary = true,
                modifier = Modifier.focusRequester(primaryFocus),
            )
            TvButton("稍后", onClick = { center.dismiss() })
        }
    }
}

@Composable
private fun FailedContent(state: UpdateState.Failed, center: UpdateCenter, primaryFocus: FocusRequester) {
    val colors = LocalTvColors.current
    val dimens = LocalTvDimens.current
    TvLabel("更新未完成", dimens.rowTitle, colors.text, weight = FontWeight.Bold)
    TvLabel(state.message, dimens.body, colors.danger, maxLines = 3)
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        TvButton(
            "关闭",
            onClick = { center.dismissFailure() },
            primary = true,
            modifier = Modifier.focusRequester(primaryFocus),
        )
    }
}

/** 体积文案：1.2 MB / 320 KB。 */
internal fun formatSize(bytes: Long): String = when {
    bytes <= 0L -> "未知大小"
    bytes >= 1024L * 1024L -> String.format(Locale.US, "%.1f MB", bytes / 1024.0 / 1024.0)
    else -> String.format(Locale.US, "%d KB", (bytes + 1023L) / 1024L)
}
