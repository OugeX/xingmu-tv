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
        SettingsCard("关于", "星幕 TV 桌面端 v0.1") {
            Text("Compose Multiplatform Desktop 1.7.3 · Kotlin 2.0.21 · libVLC(vlcj 4.8.2) · JDK 17", style = AppText.caption)
            Spacer(Modifier.height(6.dp))
            Text("与 Android 端共用 tvapp.source.v1.1 协议、assets 内置数据与 UA/Referer 约定；UI 按桌面三栏式重做。", style = AppText.caption)
            Spacer(Modifier.height(6.dp))
            Text("画中画（PiP）列为 v0.2 计划；v0.1 提供全屏与多显示器窗口拖动。", style = AppText.caption)
        }
        Spacer(Modifier.height(20.dp))
    }
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
