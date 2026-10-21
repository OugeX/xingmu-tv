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
            )
        }
    }

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
        return when (intent.getStringExtra("screen")) {
            "search" -> Dest.Search
            "sources" -> Dest.Sources
            "live" -> Dest.Live
            "casual" -> Dest.Casual
            "settings" -> Dest.Settings
            "casualVideo" -> {
                val bvid = intent.getStringExtra("bvid").orEmpty()
                if (bvid.isBlank()) null else Dest.CasualVideo(
                    clips = listOf(ShortClip(bvid = bvid, title = bvid, pic = "", durationSec = 0, owner = "", views = 0)),
                    startIndex = 0,
                )
            }
            "category" -> Dest.Category(
                intent.getIntExtra("typeId", 20),
                intent.getStringExtra("title") ?: "分类",
            )
            "detail" -> Dest.Detail(
                vodId = intent.getStringExtra("vodId").orEmpty(),
                sourceId = intent.getStringExtra("sourceId") ?: "bfzy1",
                title = intent.getStringExtra("title") ?: "",
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
