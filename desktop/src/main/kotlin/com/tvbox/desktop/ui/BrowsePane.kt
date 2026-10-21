package com.tvbox.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tvbox.desktop.data.VodSummary

@Composable
fun BrowsePane(vm: AppViewModel) {
    val section = vm.section
    Column(Modifier.fillMaxSize().padding(horizontal = 26.dp, vertical = 18.dp)) {
        SectionHeader(
            kicker = section.kicker,
            title = section.label,
            subtitle = "${vm.browseItems.size} 部 · 聚合 ${vm.activeMacCmsSources.size} 个源",
            actions = {
                GhostButton("刷新", { vm.loadBrowse(reset = true) })
            },
        )
        Spacer(Modifier.height(10.dp))
        if (vm.browseNotes.isNotEmpty()) {
            Text(
                "源状态：" + vm.browseNotes.joinToString("；"),
                style = AppText.caption.copy(fontSize = 10.sp),
                color = AppColors.textMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(6.dp))
        }
        vm.browseError?.let {
            Banner(it)
            Spacer(Modifier.height(8.dp))
        }

        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                vm.browseLoading && vm.browseItems.isEmpty() -> LoadingRow("正在聚合各源…")
                vm.browseItems.isEmpty() -> EmptyState("暂无内容", "检查源是否启用，或点击刷新")
                else -> VodGrid(
                    items = vm.browseItems,
                    selected = vm.selectedVod,
                    onSelect = { vm.selectVod(it) },
                )
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (vm.browseHasMore) {
                GhostButton(if (vm.browseLoading) "加载中…" else "加载更多", { vm.loadMore() }, enabled = !vm.browseLoading)
            }
            Spacer(Modifier.weight(1f))
            Text("第 ${vm.browsePage} 页 · 数据来自内置/导入的 MacCMS 源", style = AppText.caption)
        }
    }
}

@Composable
fun SearchPane(vm: AppViewModel) {
    Column(Modifier.fillMaxSize().padding(horizontal = 26.dp, vertical = 18.dp)) {
        SectionHeader(
            kicker = "SEARCH",
            title = "跨源搜索",
            subtitle = "并发查询 ${vm.activeMacCmsSources.size} 个点播源，同名作品自动合并",
        )
        Spacer(Modifier.height(14.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            SearchField(
                value = vm.searchQuery,
                onValueChange = { vm.searchQuery = it },
                placeholder = "输入片名，例如：流浪地球 / 庆余年",
                modifier = Modifier.width(430.dp),
            )
            Spacer(Modifier.width(10.dp))
            PrimaryButton("搜索", { vm.search(vm.searchQuery) }, enabled = !vm.searchLoading)
        }
        Spacer(Modifier.height(12.dp))
        if (vm.searchNotes.isNotEmpty()) {
            Text(
                "源状态：" + vm.searchNotes.joinToString("；"),
                style = AppText.caption.copy(fontSize = 10.sp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(6.dp))
        }
        vm.searchError?.let {
            Banner(it)
            Spacer(Modifier.height(8.dp))
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                vm.searchLoading -> LoadingRow("正在搜索各源…")
                !vm.searchPerformed -> EmptyState("输入关键词开始搜索", "搜索会并发请求所有启用的 MacCMS 源，按同名+年份合并")
                vm.searchResults.isEmpty() -> EmptyState("没有结果", "换个关键词或检查源状态")
                else -> VodGrid(vm.searchResults, vm.selectedVod, { vm.selectVod(it) })
            }
        }
    }
}

@Composable
fun VodGrid(
    items: List<VodSummary>,
    selected: VodSummary?,
    onSelect: (VodSummary) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(158.dp),
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        items(items, key = { it.sourceId + "|" + it.vodId }) { item ->
            VodCard(item, selected?.let { it.sourceId == item.sourceId && it.vodId == item.vodId } == true) { onSelect(item) }
        }
    }
}

@Composable
private fun VodCard(item: VodSummary, selected: Boolean, onClick: () -> Unit) {
    Column(Modifier.clickable(onClick = onClick)) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .clip(RoundedCornerShape(8.dp))
                .background(AppColors.surfaceHigh)
                .border(
                    width = if (selected) 2.dp else 1.dp,
                    color = if (selected) AppColors.accent else AppColors.divider,
                    shape = RoundedCornerShape(8.dp),
                ),
        ) {
            RemoteImage(item.pic, Modifier.fillMaxSize(), contentDescription = item.name)
            if (item.sourceCount > 1) {
                Box(Modifier.align(Alignment.TopStart).padding(6.dp)) {
                    Tag("${item.sourceCount} 源", AppColors.accent, AppColors.accent.copy(alpha = 0.2f))
                }
            }
            if (item.score.isNotBlank() && item.score != "0.0") {
                Box(Modifier.align(Alignment.TopEnd).padding(6.dp)) {
                    Tag(item.score, AppColors.text, Color.Black.copy(alpha = 0.55f))
                }
            }
            if (item.remarks.isNotBlank()) {
                Box(
                    Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth()
                        .background(Color.Black.copy(alpha = 0.55f))
                        .padding(horizontal = 6.dp, vertical = 3.dp),
                ) {
                    Text(item.remarks, style = AppText.caption.copy(color = AppColors.textSecondary, fontSize = 10.sp), maxLines = 1)
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            item.name,
            style = AppText.bodyStrong.copy(fontSize = 12.sp, color = if (selected) AppColors.accent else AppColors.text),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            listOfNotNull(item.year.takeIf { it.isNotBlank() }, item.typeName.takeIf { it.isNotBlank() }).joinToString(" · "),
            style = AppText.caption.copy(fontSize = 10.sp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
