package com.tvbox.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.LinearProgressIndicator
import androidx.compose.material.Slider
import androidx.compose.material.SliderDefaults
import androidx.compose.material.Switch
import androidx.compose.material.SwitchDefaults
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tvbox.desktop.BuildInfo
import com.tvbox.desktop.update.UpdateState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun SettingsPane(vm: AppViewModel) {
    var vlcPath by remember(vm.settings.vlcHome) { mutableStateOf(vm.settings.vlcHome) }
    var volume by remember(vm.settings.volume) { mutableStateOf(vm.settings.volume.toFloat()) }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 26.dp, vertical = 18.dp),
    ) {
        SectionHeader(
            kicker = "SETTINGS",
            title = "设置",
            subtitle = "播放器 / 路径 / 缓存",
            actions = { GhostButton("重新检测 VLC", { vm.detectVlc() }) },
        )
        Spacer(Modifier.height(16.dp))

        SettingsCard("libVLC 播放器", "直播与点播共用 libVLC 3.x（vlcj 4.8.2）") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Tag(if (vm.vlcRuntime != null) "已找到" else "未找到", if (vm.vlcRuntime != null) AppColors.success else AppColors.danger)
                Spacer(Modifier.width(8.dp))
                Text(vm.vlcMessage, style = AppText.body.copy(fontSize = 11.sp), modifier = Modifier.weight(1f))
            }
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                DarkTextField(
                    value = vlcPath,
                    onValueChange = { vlcPath = it },
                    placeholder = "VLC 安装目录，例如 D:\\VideoLAN\\VLC 或 /Applications/VLC.app",
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                PrimaryButton("保存并检测", { vm.setVlcHome(vlcPath.trim()) })
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GhostButton("打开发布页", { vm.openExternal("https://www.videolan.org/vlc/") })
                Text(
                    "可设置环境变量 TVAPP_VLC_HOME 指向 VLC 目录，或把 libvlc 放到 PATH。",
                    style = AppText.caption.copy(fontSize = 10.sp),
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }

        Spacer(Modifier.height(12.dp))
        SettingsCard("播放偏好", "画质上限会在播放前过滤线路；解码失败自动降级") {
            Text("画质上限", style = AppText.caption)
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("auto" to "自动", "1080p" to "1080p", "720p" to "720p", "480p" to "480p").forEach { (value, label) ->
                    Chip(label, vm.settings.maxQuality == value, { vm.setMaxQuality(value) })
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("优先硬件解码", style = AppText.bodyStrong.copy(fontSize = 12.sp))
                    Text("关闭后强制软解（1080p 黑屏排查用）", style = AppText.caption.copy(fontSize = 10.sp))
                }
                Switch(
                    checked = vm.settings.hardwareDecode,
                    onCheckedChange = { vm.setHardwareDecode(it) },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = AppColors.accentOn,
                        checkedTrackColor = AppColors.accent,
                        uncheckedThumbColor = AppColors.textMuted,
                        uncheckedTrackColor = AppColors.surfaceHigh,
                    ),
                )
            }
            Spacer(Modifier.height(10.dp))
            Text("默认音量 · ${volume.toInt()}%", style = AppText.caption)
            Slider(
                value = volume,
                onValueChange = {
                    volume = it
                    vm.player.setVolume(it.toInt())
                },
                onValueChangeFinished = { vm.setVolume(volume.toInt()) },
                colors = SliderDefaults.colors(
                    thumbColor = AppColors.accent,
                    activeTrackColor = AppColors.accent,
                    inactiveTrackColor = AppColors.divider,
                ),
            )
        }

        Spacer(Modifier.height(12.dp))
        SettingsCard("数据与缓存", "SWR 缓存 5 分钟（列表）/ 30 分钟（详情）") {
            InfoRow("配置目录", vm.paths.configDir.toString())
            InfoRow("缓存目录", vm.paths.cacheDir.toString())
            InfoRow("内置数据", vm.paths.assetsSource)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GhostButton("清空 HTTP 缓存", { vm.clearCache() })
                GhostButton("打开配置目录", { vm.openExternal(vm.paths.configDir.toUri().toString()) })
            }
        }

        Spacer(Modifier.height(12.dp))
        SettingsCard("快捷键", "键盘鼠标桌面交互（不复用 TV 焦点模型）") {
            ShortcutRow("空格", "暂停 / 继续")
            ShortcutRow("← / →", "快退 / 快进 10 秒")
            ShortcutRow("↑ / ↓", "音量 ±5%")
            ShortcutRow("F", "进入 / 退出全屏")
            ShortcutRow("Esc", "退出全屏；再按停止播放")
        }

        Spacer(Modifier.height(12.dp))
        SettingsCard("关于", "星幕 TV 桌面端 v${BuildInfo.version}") {
            UpdateAboutRows(vm)
            Spacer(Modifier.height(10.dp))
            Text("Compose Multiplatform Desktop 1.7.3 · Kotlin 2.0.21 · libVLC(vlcj 4.8.2) · JDK 17", style = AppText.caption)
            Spacer(Modifier.height(6.dp))
            Text("与 Android 端共用 tvapp.source.v1.1 协议、assets 内置数据与 UA/Referer 约定；UI 按桌面三栏式重做。", style = AppText.caption)
            Spacer(Modifier.height(6.dp))
            Text("v0.2：播放进度/继续观看、收藏、直播最近、直播分组与地方分页；更新检查与 Android 同规则：访问失败静默。", style = AppText.caption)
            Spacer(Modifier.height(6.dp))
            Text("画中画（PiP）列为后续计划；macOS 包未在本机构建（PENDING_DEVICE）。", style = AppText.caption)
        }
        Spacer(Modifier.height(20.dp))
    }
}

@Composable
private fun UpdateAboutRows(vm: AppViewModel) {
    val state = vm.updateCenter.state
    val checking = state is UpdateState.Checking

    InfoRow("当前版本", "v${BuildInfo.version}")
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("检查更新", style = AppText.caption, modifier = Modifier.width(80.dp))
        PrimaryButton(
            text = if (checking) "检查中…" else "检查更新",
            onClick = { vm.checkForUpdates() },
            enabled = !checking,
        )
        Spacer(Modifier.width(10.dp))
        Text(updateStatusText(state), style = AppText.caption.copy(color = AppColors.textSecondary), maxLines = 1)
    }
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("自动检查", style = AppText.caption, modifier = Modifier.width(80.dp))
        Switch(
            checked = vm.settings.autoCheckUpdate,
            onCheckedChange = { vm.setAutoCheckUpdate(it) },
            colors = SwitchDefaults.colors(
                checkedThumbColor = AppColors.accentOn,
                checkedTrackColor = AppColors.accent,
                uncheckedThumbColor = AppColors.textMuted,
                uncheckedTrackColor = AppColors.surfaceHigh,
            ),
        )
        Spacer(Modifier.width(10.dp))
        Text("启动 8s 后检查，之后每 24h 一次", style = AppText.caption.copy(fontSize = 10.sp))
    }
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("包含测试版", style = AppText.caption, modifier = Modifier.width(80.dp))
        Switch(
            checked = vm.settings.includePrereleaseUpdate,
            onCheckedChange = { vm.setIncludePrereleaseUpdate(it) },
            colors = SwitchDefaults.colors(
                checkedThumbColor = AppColors.accentOn,
                checkedTrackColor = AppColors.accent,
                uncheckedThumbColor = AppColors.textMuted,
                uncheckedTrackColor = AppColors.surfaceHigh,
            ),
        )
        Spacer(Modifier.width(10.dp))
        Text("预发布版本默认不提示", style = AppText.caption.copy(fontSize = 10.sp))
    }
    InfoRow("上次检查", formatCheckTime(vm.settings.lastUpdateCheckAt))

    when (state) {
        is UpdateState.Available -> {
            Spacer(Modifier.height(6.dp))
            Text(
                if (state.candidate.pageOnly) {
                    "发现 ${state.candidate.release.tag}（Release 暂无桌面安装包）"
                } else {
                    "发现 v${state.candidate.displayVersion}（${state.candidate.asset?.name ?: "安装包"}）"
                },
                style = AppText.body.copy(fontSize = 11.sp, color = AppColors.accent),
            )
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (state.candidate.pageOnly) {
                    PrimaryButton("打开 Release 页面", { vm.openUpdateReleasePage() })
                } else {
                    PrimaryButton("下载并安装", { vm.downloadUpdate() })
                }
            }
        }
        is UpdateState.Downloading -> {
            Spacer(Modifier.height(6.dp))
            Text("正在下载 ${state.percent}%", style = AppText.body.copy(fontSize = 11.sp))
            Spacer(Modifier.height(4.dp))
            LinearProgressIndicator(
                progress = state.percent / 100f,
                modifier = Modifier.fillMaxWidth().height(4.dp),
                color = AppColors.accent,
                backgroundColor = AppColors.surfaceHigh,
            )
            Spacer(Modifier.height(6.dp))
            GhostButton("取消下载", { vm.cancelUpdateDownload() })
        }
        is UpdateState.Ready -> {
            Spacer(Modifier.height(6.dp))
            Text("安装包已就绪", style = AppText.body.copy(fontSize = 11.sp, color = AppColors.success))
            Text(state.file.toString(), style = AppText.mono.copy(fontSize = 9.sp), maxLines = 1)
            Spacer(Modifier.height(6.dp))
            PrimaryButton("打开安装包", { vm.openDownloadedInstaller() })
        }
        is UpdateState.Failed -> {
            Spacer(Modifier.height(6.dp))
            Text(state.message, style = AppText.caption.copy(color = AppColors.danger))
            Spacer(Modifier.height(6.dp))
            GhostButton("重新检查", { vm.checkForUpdates() })
        }
        is UpdateState.UpToDate -> {
            Spacer(Modifier.height(6.dp))
            Text("已是最新（${formatCheckTime(state.checkedAt)}）", style = AppText.caption)
        }
        else -> Unit
    }

    Spacer(Modifier.height(8.dp))
    Text(
        "更新检查走 GitHub API，国内网络可能访问不了；失败时静默跳过，不影响使用。" +
            "桌面端只做「下载 + 打开安装包交给系统安装器」，不静默安装。",
        style = AppText.caption.copy(fontSize = 10.sp),
    )
}

private fun updateStatusText(state: UpdateState): String = when (state) {
    is UpdateState.Idle -> "手动检查不受频率限制"
    is UpdateState.Checking -> "检查中…"
    is UpdateState.UpToDate -> "已是最新"
    is UpdateState.Available ->
        if (state.candidate.pageOnly) "发现 ${state.candidate.release.tag}（仅 Release 页面）"
        else "发现 v${state.candidate.displayVersion}"
    is UpdateState.Downloading -> "正在下载 ${state.percent}%"
    is UpdateState.Ready -> "安装包已就绪"
    is UpdateState.Failed -> state.message
}

private fun formatCheckTime(at: Long): String {
    if (at <= 0L) return "从未"
    return SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(at))
}

@Composable
private fun SettingsCard(title: String, subtitle: String, content: @Composable () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(AppColors.surface, RoundedCornerShape(10.dp))
            .padding(16.dp),
    ) {
        Text(title, style = AppText.title.copy(fontSize = 15.sp))
        Spacer(Modifier.height(2.dp))
        Text(subtitle, style = AppText.caption.copy(fontSize = 10.sp))
        Spacer(Modifier.height(12.dp))
        content()
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(label, style = AppText.caption, modifier = Modifier.width(80.dp))
        Text(value, style = AppText.mono.copy(fontSize = 10.sp), modifier = Modifier.weight(1f))
    }
}

@Composable
private fun ShortcutRow(key: String, description: String) {
    Row(Modifier.padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        Tag(key, AppColors.accent)
        Spacer(Modifier.width(10.dp))
        Text(description, style = AppText.caption)
    }
}
