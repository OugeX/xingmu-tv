package com.tvbox.app.ui.casual

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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.tvbox.app.data.casual.CasualGraph
import com.tvbox.app.data.casual.CasualRepository
import com.tvbox.app.data.casual.ShortClip
import com.tvbox.app.ui.LocalTvColors
import com.tvbox.app.ui.LocalTvDimens
import com.tvbox.app.ui.components.TvButton
import com.tvbox.app.ui.components.TvLabel

/**
 * 一级入口「随便」（随便听听看看）：听 / 看 两个分段共用一套数据层与播放器。
 * 听：进入即播 + 跨页面持续出声；看：B 站公开榜单短视频。
 */
@Composable
fun CasualScreen(
    repo: CasualRepository,
    onPlayVideo: (List<ShortClip>, Int) -> Unit,
    initialSegment: Int = 0,
) {
    val context = LocalContext.current
    val colors = LocalTvColors.current
    val dimens = LocalTvDimens.current
    val session = remember(context) { CasualGraph.radioSession(context) }

    var segment by remember { mutableIntStateOf(initialSegment.coerceIn(0, 1)) }
    var showAbout by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TvLabel("随便", dimens.rowTitle, colors.accent, weight = FontWeight.Black)
            Spacer(Modifier.width(8.dp))
            TvLabel("随便听听看看", dimens.caption, colors.textMuted)
            Spacer(Modifier.width(18.dp))
            TvButton("听", onClick = { segment = 0 }, primary = segment == 0)
            Spacer(Modifier.width(8.dp))
            TvButton("看", onClick = { segment = 1 }, primary = segment == 1)
            Spacer(Modifier.width(1.dp).weight(1f))
            TvButton("关于/边界", onClick = { showAbout = true })
        }
        Box(Modifier.weight(1f)) {
            if (segment == 0) {
                CasualRadioScreen(
                    session = session,
                    modifier = Modifier.fillMaxSize(),
                    onOpenClips = { segment = 1 },
                )
            } else {
                CasualClipsScreen(
                    repo = repo,
                    onPlayVideo = onPlayVideo,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }

    if (showAbout) {
        CasualAboutOverlay(onClose = { showAbout = false })
    }
}

/** 边界说明（CASUAL_SPEC §4；也承担“设置/关于”页说明职责）。 */
@Composable
fun CasualAboutOverlay(onClose: () -> Unit) {
    val colors = LocalTvColors.current
    val dimens = LocalTvDimens.current
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.92f))
            .padding(36.dp),
    ) {
        Column(
            Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TvLabel("随便听听看看 · 说明与边界", dimens.rowTitle, colors.text, weight = FontWeight.Bold)
                Spacer(Modifier.width(1.dp).weight(1f))
                TvButton("关闭", onClick = onClose)
            }
            TvLabel(
                "1. 内容来自公开接口（Radio Browser / B 站公开榜单），仅用于个人收听与观看，不提供下载，也不把内容缓存到本地。",
                dimens.body,
                colors.textSecondary,
                maxLines = 4,
            )
            TvLabel(
                "2. 未登录 B 站只能拿到 360/480 档，画质有限；本应用不内嵌登录、不保存 Cookie。",
                dimens.body,
                colors.textSecondary,
                maxLines = 4,
            )
            TvLabel(
                "3. 电台可用性由第三方决定，随时可能失效；失效会自动跳过并切到下一个台，不保证每个台都能听。",
                dimens.body,
                colors.textSecondary,
                maxLines = 4,
            )
            TvLabel(
                "4. 不接抖音/快手：无公开接口，抓私有接口属违规且极易失效。",
                dimens.body,
                colors.textSecondary,
                maxLines = 4,
            )
            Spacer(Modifier.height(6.dp))
            TvLabel(
                "快捷键：听页 CH+/CH- 或 ↑↓ 切台、OK 暂停、MENU 电台列表、← 回侧栏、BACK 回首页；看页 OK 播放、播完自动下一条。",
                dimens.caption,
                colors.textMuted,
                maxLines = 3,
            )
        }
    }
}
