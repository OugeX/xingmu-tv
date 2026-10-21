package com.tvbox.desktop.smoke

import com.tvbox.desktop.core.Log
import com.tvbox.desktop.data.AppPaths
import com.tvbox.desktop.data.FileCache
import com.tvbox.desktop.data.Http
import com.tvbox.desktop.data.LiveSeedLoader
import com.tvbox.desktop.data.MacCmsClient
import com.tvbox.desktop.data.PlaybackRequest
import com.tvbox.desktop.data.PlayLine
import com.tvbox.desktop.data.SourceKind
import com.tvbox.desktop.data.SourceStore
import com.tvbox.desktop.player.PlaybackLadder
import com.tvbox.desktop.player.VlcLocator
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject
import uk.co.caprica.vlcj.factory.MediaPlayerFactory
import uk.co.caprica.vlcj.player.base.MediaPlayer
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

/**
 * 非交互自检：`gradle run --args="--smoke"`。
 *
 * 验证链路：内置源加载 → 直播种子解析 → MacCMS list/detail → libVLC 实播一条直播 + 一条点播。
 * 结果写 JSON（默认 .artifacts/desktop/smoke.json），退出码 0 表示全部通过。
 */
class SmokeRunner(
    private val paths: AppPaths,
    private val outFile: Path?,
) {
    private val checks = mutableListOf<Check>()

    data class Check(val name: String, val ok: Boolean, val detail: String)

    data class VlcPlayReport(
        val ok: Boolean,
        val url: String,
        val label: String,
        val state: String,
        val elapsedMs: Long,
        val timeMs: Long,
        val lengthMs: Long,
        val attempts: Int,
    )

    suspend fun run(): Boolean {
        val started = System.currentTimeMillis()
        val http = Http()
        val cache = FileCache(paths.cacheDir.resolve("http"))
        val sourceStore = SourceStore(paths, http)
        val macCms = MacCmsClient(http, cache)

        // 1) 内置源（仓库 assets/sources.builtin.json，不复制）
        val builtin = sourceStore.loadBuiltin()
        val maccmsSources = builtin.sources.filter { it.config.kind == SourceKind.MACCMS }
        add(
            "assets.builtin",
            builtin.sources.isNotEmpty() && builtin.issues.isEmpty(),
            "加载 ${builtin.sources.size} 个源（MacCMS ${maccmsSources.size}）来源=${builtin.source} 校验问题=${builtin.issues.size}" +
                builtin.issues.firstOrNull()?.let { "；首个：$it" }.orEmpty(),
        )
        add("assets.relative", paths.assetsDir != null, "assets=${paths.assetsDir ?: "jar 兜底"}（${paths.assetsSource}）")

        // 2) 直播种子
        val live = LiveSeedLoader.load(paths)
        add(
            "live.seed",
            live.channels.isNotEmpty() && live.groups.isNotEmpty(),
            "频道 ${live.channels.size} / 分组 ${live.groups.size} / generatedAt=${live.generatedAt} / ${live.source}",
        )
        val liveChannel = live.channels.firstOrNull { it.id == "cctv1" }
            ?: live.channels.firstOrNull { it.healthy && it.lines.isNotEmpty() }
            ?: live.channels.firstOrNull { it.lines.isNotEmpty() }
        add(
            "live.pick",
            liveChannel != null,
            liveChannel?.let { "选中 ${it.name}（${it.group}，${it.lines.size} 线路，${it.qualityLabel}）" } ?: "没有可用频道",
        )

        // 3) MacCMS list + detail
        val source = maccmsSources.firstOrNull { it.config.baseUrl.contains("macapi1.com") }?.config
            ?: maccmsSources.firstOrNull()?.config
        add("maccms.source", source != null, source?.let { "${it.name} ${it.baseUrl}" } ?: "无 MacCMS 源")

        var vodUrl: String? = null
        var vodTitle = ""
        var vodDetailInfo = ""
        if (source != null) {
            val classes = macCms.classes(source)
            val classList = classes.value.orEmpty()
            add(
                "maccms.classes",
                classList.isNotEmpty(),
                if (classList.isNotEmpty()) "ac=list 返回 ${classList.size} 个分类，示例=${classList.take(3).joinToString { "${it.typeName}(${it.typeId})" }}"
                else "失败：${classes.error?.message}",
            )
            val list = macCms.list(source, "MOVIE", 1)
            val first = list.value?.firstOrNull()
            add(
                "maccms.list",
                first != null,
                if (first != null) "返回 ${list.value?.size} 条（stale=${list.stale}），首条 ${first.name}(${first.vodId})"
                else "失败：${list.error?.message}",
            )
            if (first != null) {
                val detail = macCms.detail(source, first.vodId)
                val d = detail.value
                add(
                    "maccms.detail",
                    d?.firstEpisode != null,
                    if (d != null) {
                        val episode = d.firstEpisode
                        vodUrl = episode?.url
                        vodTitle = "${d.name} · ${episode?.title}"
                        vodDetailInfo = "线路 ${d.playFroms.joinToString("/")}，分集 ${d.episodesByFrom.values.sumOf { it.size }}，首集=${episode?.title}"
                        "《${d.name}》$vodDetailInfo"
                    } else {
                        "失败：${detail.error?.message}"
                    },
                )
            }
        }
        add("vod.url", vodUrl != null, vodUrl ?: "没有拿到点播地址")

        // 4) 直播地址 HTTP 可达性（不依赖 VLC，先证明 URL 有效）
        val liveUrl = liveChannel?.bestLine?.url
        if (liveUrl != null) {
            val reachable = runCatching {
                val head = http.getBytes(liveUrl, emptyMap(), 12_000, maxBytes = 512 * 1024)
                val text = head.toString(Charsets.UTF_8)
                text.contains("#EXTM3U")
            }.getOrDefault(false)
            add("live.hls", reachable, "GET $liveUrl 返回 #EXTM3U=$reachable")
        } else {
            add("live.hls", false, "没有直播地址")
        }

        // 5) 降级阶梯（静态检查：1080p → 软解 → 720p）
        val ladder = PlaybackLadder.build(
            PlaybackRequest(
                title = "ladder",
                subtitle = "",
                isLive = true,
                lines = listOf(
                    PlayLine("主线路", "https://example.com/1080.m3u8", "1080p"),
                    PlayLine("备用", "https://example.com/720.m3u8", "720p"),
                ),
            ),
            hardwareDecode = true,
            maxQuality = "auto",
        )
        add(
            "playback.ladder",
            ladder.size >= 4 && ladder[0].quality == "1080p" && !ladder[0].softwareDecode &&
                ladder[1].softwareDecode && ladder.last().quality == "720p",
            ladder.joinToString(" → ") { it.label },
        )

        // 6) libVLC 原生库
        val vlcResult = VlcLocator.locate(System.getProperty("tvapp.vlc.home"))
        add("vlc.locate", vlcResult.ok, if (vlcResult.ok) vlcResult.runtime!!.displayPath else vlcResult.message)
        var vlcVersion: String? = null
        var liveReport: VlcPlayReport? = null
        var vodReport: VlcPlayReport? = null
        if (vlcResult.runtime != null) {
            VlcLocator.configure(vlcResult.runtime)
            if (liveChannel != null) {
                val candidates = buildList {
                    add(liveChannel)
                    live.channels
                        .filter { it.healthy && it.lines.isNotEmpty() && it.id != liveChannel.id }
                        .take(3)
                        .forEach { add(it) }
                }
                val failures = mutableListOf<String>()
                for (channel in candidates) {
                    for ((index, line) in channel.lines.take(3).withIndex()) {
                        val report = playWithVlc(
                            vlcResult.runtime,
                            line.url,
                            "直播 ${channel.name} · 线路${index + 1}",
                            isLive = true,
                            timeoutMs = 22_000,
                        )
                        if (report.ok) {
                            liveReport = report
                            break
                        }
                        failures += "${channel.name}#${index + 1} state=${report.state} time=${report.timeMs}"
                    }
                    if (liveReport != null) break
                }
                add(
                    "vlc.live.play",
                    liveReport != null,
                    liveReport?.describe() ?: "所有候选直播源均失败：${failures.joinToString(" | ")}",
                )
            }
            if (vodUrl != null) {
                vodReport = playWithVlc(vlcResult.runtime, vodUrl, vodTitle, isLive = false)
                add("vlc.vod.play", vodReport.ok, vodReport.describe())
            }
            vlcVersion = runCatching {
                val factory = MediaPlayerFactory("--vout=dummy", "--aout=dummy")
                try {
                    factory.application().version()
                } finally {
                    factory.release()
                }
            }.getOrNull()
        }

        val ok = checks.all { it.ok }
        val elapsed = System.currentTimeMillis() - started
        val json = JSONObject()
            .put("ok", ok)
            .put("time", Instant.now().toString())
            .put("elapsedMs", elapsed)
            .put("platform", "${System.getProperty("os.name")} ${System.getProperty("os.version")}")
            .put("assetsDir", paths.assetsDir?.toString() ?: "")
            .put("assetsSource", paths.assetsSource)
            .put("configDir", paths.configDir.toString())
            .put("vlcPath", vlcResult.runtime?.displayPath ?: "")
            .put("vlcVersion", vlcVersion ?: "")
            .put("builtinSources", builtin.sources.size)
            .put("liveChannels", live.channels.size)
            .put("liveGroups", live.groups.size)
            .put("checks", JSONArray().also { arr -> checks.forEach { arr.put(JSONObject().put("name", it.name).put("ok", it.ok).put("detail", it.detail)) } })
            .put("livePlay", liveReport?.toJson() ?: JSONObject.NULL)
            .put("vodPlay", vodReport?.toJson() ?: JSONObject.NULL)

        val text = json.toString(2)
        println("\n===== SMOKE RESULT =====")
        println(text)
        println("========================")
        outFile?.let { file ->
            runCatching {
                Files.createDirectories(file.toAbsolutePath().parent)
                Files.writeString(file, text)
                Log.i("Smoke", "结果写入 $file")
            }.onFailure { Log.e("Smoke", "写结果失败", it) }
        }
        return ok
    }

    private fun playWithVlc(
        runtime: VlcLocator.Runtime,
        url: String,
        label: String,
        isLive: Boolean,
        timeoutMs: Long = 35_000,
    ): VlcPlayReport {
        val started = System.currentTimeMillis()
        var stateText = "INIT"
        var timeMs = 0L
        var lengthMs = 0L
        var played = false
        var player: MediaPlayer? = null
        var factory: MediaPlayerFactory? = null
        try {
            VlcLocator.configure(runtime)
            factory = MediaPlayerFactory("--vout=dummy", "--aout=dummy", "--no-video-title-show")
            player = factory.mediaPlayers().newMediaPlayer()
            player.media().play(
                url,
                ":network-caching=1500",
                ":http-referrer=${Http.DEFAULT_REFERER}",
                ":http-user-agent=${Http.DEFAULT_UA}",
            )
            val deadline = started + timeoutMs
            while (System.currentTimeMillis() < deadline) {
                Thread.sleep(350)
                val status = runCatching { player.status() }.getOrNull() ?: break
                stateText = status.state().name
                timeMs = maxOf(timeMs, status.time())
                lengthMs = maxOf(lengthMs, status.length())
                if (status.isPlaying && timeMs > 0) {
                    played = true
                    if (timeMs > if (isLive) 1_500 else 800) break
                }
                if (stateText == "ERROR") break
                if (stateText == "STOPPED" && timeMs == 0L && System.currentTimeMillis() - started > 2_500) break
            }
        } catch (t: Throwable) {
            Log.e("Smoke", "VLC 播放异常 $label", t)
            stateText = "EXCEPTION:${t.message}"
        } finally {
            runCatching { player?.release() }
            runCatching { factory?.release() }
        }
        return VlcPlayReport(
            ok = played,
            url = url,
            label = label,
            state = stateText,
            elapsedMs = System.currentTimeMillis() - started,
            timeMs = timeMs,
            lengthMs = lengthMs,
            attempts = 1,
        )
    }

    private fun VlcPlayReport.describe(): String =
        "$label state=$state time=${timeMs}ms length=${lengthMs}ms elapsed=${elapsedMs}ms url=$url"

    private fun VlcPlayReport.toJson(): JSONObject = JSONObject()
        .put("ok", ok)
        .put("label", label)
        .put("url", url)
        .put("state", state)
        .put("elapsedMs", elapsedMs)
        .put("timeMs", timeMs)
        .put("lengthMs", lengthMs)

    private fun add(name: String, ok: Boolean, detail: String) {
        checks += Check(name, ok, detail)
        val tag = if (ok) "PASS" else "FAIL"
        println("[$tag] $name :: $detail")
    }
}
