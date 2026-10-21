package com.tvbox.desktop.ui

import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Divider
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tvbox.desktop.data.SourceConfig
import com.tvbox.desktop.data.SourceKind
import javax.swing.JFileChooser
import javax.swing.filechooser.FileNameExtensionFilter

private enum class ImportMode { NONE, PASTE, URL }

@Composable
fun SourcesPane(vm: AppViewModel) {
    var mode by remember { mutableStateOf(ImportMode.NONE) }
    var url by remember { mutableStateOf("") }

    Column(Modifier.fillMaxSize().padding(horizontal = 26.dp, vertical = 18.dp)) {
        SectionHeader(
            kicker = "SOURCES",
            title = "源管理",
            subtitle = "内置 ${vm.sources.count { !vm.isUserSource(it.id) }} · 用户 ${vm.sources.count { vm.isUserSource(it.id) }} · 协议 tvapp.source.v1.1",
            actions = {
                GhostButton("刷新", { vm.refresh() })
            },
        )
        Spacer(Modifier.height(12.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PrimaryButton("导入文件", {
                val chooser = JFileChooser().apply {
                    fileFilter = FileNameExtensionFilter("源配置 JSON (*.json)", "json")
                }
                if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
                    vm.importFromFile(chooser.selectedFile.toPath())
                }
            })
            GhostButton("粘贴文本", {
                mode = if (mode == ImportMode.PASTE) ImportMode.NONE else ImportMode.PASTE
                vm.clearImportState()
            })
            GhostButton("从 URL 导入", {
                mode = if (mode == ImportMode.URL) ImportMode.NONE else ImportMode.URL
            })
            Spacer(Modifier.weight(1f))
            Text("校验错误码与 tools/validate_sources.py 对齐", style = AppText.caption.copy(fontSize = 10.sp))
        }

        when (mode) {
            ImportMode.PASTE -> {
                Spacer(Modifier.height(10.dp))
                DarkTextField(
                    value = vm.sourceImportText,
                    onValueChange = { vm.sourceImportText = it },
                    placeholder = "粘贴 tvapp.source.v1 / v1.1 JSON：单源，或 {kind:bundle, sources:[...]} 合集",
                    modifier = Modifier.fillMaxWidth().height(150.dp),
                    singleLine = false,
                    minLines = 6,
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PrimaryButton("校验并导入", { vm.importFromText() }, enabled = !vm.importing)
                    GhostButton("清空", { vm.clearImportState() })
                }
            }
            ImportMode.URL -> {
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    DarkTextField(
                        value = url,
                        onValueChange = { url = it },
                        placeholder = "https://example.com/sources.json（订阅合集或单源）",
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(8.dp))
                    PrimaryButton("下载并导入", { vm.importFromUrl(url) }, enabled = !vm.importing)
                }
            }
            ImportMode.NONE -> Unit
        }

        if (vm.importing) {
            Spacer(Modifier.height(8.dp))
            LoadingRow("正在校验…")
        }
        vm.sourceImportMessage?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, style = AppText.caption.copy(color = AppColors.textSecondary))
        }
        if (vm.sourceImportIssues.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Column(Modifier.fillMaxWidth().height(160.dp).verticalScroll(rememberScrollState())) {
                vm.sourceImportIssues.forEach { issue ->
                    Text(
                        "${issue.code}  ${issue.path}  ${issue.message}",
                        style = AppText.caption.copy(fontSize = 10.sp, color = AppColors.danger),
                        modifier = Modifier.padding(vertical = 1.dp),
                    )
                }
            }
        }

        Spacer(Modifier.height(12.dp))
        Divider(color = AppColors.divider)
        Spacer(Modifier.height(8.dp))

        if (vm.builtinIssueCount > 0) {
            Banner("内置源有 ${vm.builtinIssueCount} 条校验警告（不影响已通过的源）", AppColors.warning)
            Spacer(Modifier.height(8.dp))
        }

        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(vm.sources, key = { it.id }) { source ->
                SourceCard(vm, source)
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            "内置源来自仓库 assets/sources.builtin.json（相对引用，未复制）；用户导入保存在配置目录 sources.user.json。",
            style = AppText.caption.copy(fontSize = 10.sp),
        )
    }
}

@Composable
private fun SourceCard(vm: AppViewModel, source: SourceConfig) {
    val user = vm.isUserSource(source.id)
    Row(
        Modifier
            .fillMaxWidth()
            .background(AppColors.surface, RoundedCornerShape(8.dp))
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(source.name, style = AppText.bodyStrong.copy(fontSize = 13.sp))
                Spacer(Modifier.width(8.dp))
                Tag(source.kind.name.lowercase(), AppColors.textMuted)
                if (!user) {
                    Spacer(Modifier.width(6.dp))
                    Tag("内置", AppColors.accent)
                }
                if (source.enabled) {
                    Spacer(Modifier.width(6.dp))
                    Tag("已启用", AppColors.success)
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                source.displayEndpoint,
                style = AppText.mono.copy(fontSize = 10.sp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                "id=${source.id} · priority=${source.priority} · timeout=${source.timeoutMs}ms" +
                    if (source.kind == SourceKind.MACCMS) " · ${source.categories.size} 个分类" else "",
                style = AppText.caption.copy(fontSize = 10.sp),
            )
            vm.sourceProbe[source.id]?.let {
                Spacer(Modifier.height(3.dp))
                Text(it, style = AppText.caption.copy(fontSize = 10.sp, color = AppColors.textSecondary))
            }
        }
        Column(horizontalAlignment = Alignment.End) {
            Switch(
                checked = source.enabled,
                onCheckedChange = { vm.toggleSource(source.id, it) },
                colors = SwitchDefaults.colors(
                    checkedThumbColor = AppColors.accentOn,
                    checkedTrackColor = AppColors.accent,
                    uncheckedThumbColor = AppColors.textMuted,
                    uncheckedTrackColor = AppColors.surfaceHigh,
                ),
            )
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                GhostButton("测试", { vm.probeSource(source) }, enabled = source.kind == SourceKind.MACCMS)
                if (user) GhostButton("删除", { vm.removeUserSource(source.id) }, danger = true)
            }
        }
    }
}
