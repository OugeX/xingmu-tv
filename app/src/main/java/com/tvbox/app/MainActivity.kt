package com.tvbox.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.tvbox.app.core.TvLog
import com.tvbox.app.data.casual.ShortClip
import com.tvbox.app.ui.Dest
import com.tvbox.app.ui.TvBoxApp
import com.tvbox.app.ui.TvMode

/**
 * 唯一 Activity（TV 清单：landscape / leanback launcher / banner，见 AndroidManifest.xml）。
 * 页面内容全部在 `com.tvbox.app.ui.TvBoxApp`；本文件不再放业务逻辑。
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        TvLog.i("MainActivity", "onCreate")
        setContent {
            TvBoxApp(
                initialMode = pickMode(),
                initialDest = destFromIntent(),
                casualSegment = intent?.getIntExtra("casualSegment", 0) ?: 0,
                initialSearchQuery = deepLinkQuery,
            )
        }
    }

    /** 深链搜索词（--es screen search --es q 关键词），由 TvBoxApp 传给搜索页。 */
    private var deepLinkQuery: String = ""

    /** singleTask 下再次收到带 screen 的调试 Intent 时重建页面，避免停留在旧页面。 */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.hasExtra("screen") || intent.hasExtra("casualSegment")) {
            recreate()
        }
    }

    /** 支持 adb/一键直达（也方便 QA 自动截图）：--es screen search|sources|live|common|category|detail|casual|casualVideo。 */
    private fun destFromIntent(): Dest? {
        val intent = intent ?: return null
        val extras = intent.extras
        fun text(vararg keys: String): String? {
            if (extras == null) return null
            for (key in keys) {
                val value = extras.get(key) ?: continue
                val asText = value.toString().trim()
                if (asText.isNotEmpty()) return asText
            }
            return null
        }
        val screen = text("screen")
        deepLinkQuery = text("q", "keyword").orEmpty()
        TvLog.i("DeepLink", "screen=$screen vodId=${text("vodId", "vid", "id")} sourceId=${text("sourceId")} title=${text("title")}")
        return when (screen) {
            "search" -> Dest.Search
            "sources" -> Dest.Sources
            "live" -> Dest.Live
            "casual" -> Dest.Casual
            "settings" -> Dest.Settings
            "casualVideo" -> {
                val bvid = text("bvid").orEmpty()
                if (bvid.isBlank()) null else Dest.CasualVideo(
                    clips = listOf(ShortClip(bvid = bvid, title = bvid, pic = "", durationSec = 0, owner = "", views = 0)),
                    startIndex = 0,
                )
            }
            "category" -> Dest.Category(
                intent.getIntExtra("typeId", 20),
                text("title") ?: "分类",
            )
            "detail" -> Dest.Detail(
                vodId = text("vodId", "vid", "id").orEmpty(),
                sourceId = text("sourceId") ?: "bfzy1",
                title = text("title") ?: "",
            )
            else -> null
        }
    }

    /**
     * 模式判定：低内存设备直接进 C 保命模式；其余默认 A 影院模式（用户可在左侧栏切换）。
     * 不做 API 24+ 调用（真机 Android 6 / SDK 23）。
     */
    private fun pickMode(): TvMode {
        val am = getSystemService(ACTIVITY_SERVICE) as android.app.ActivityManager
        return if (am.isLowRamDevice) TvMode.LITE else TvMode.CINEMA
    }
}
