package com.tvbox.app.data.casual

import android.content.Context
import com.tvbox.app.core.AppDispatchers
import com.tvbox.app.core.AppError
import com.tvbox.app.core.ErrorCodes
import com.tvbox.app.core.Result
import com.tvbox.app.core.TvLog
import com.tvbox.app.data.FileCache
import com.tvbox.app.data.Http
import com.tvbox.app.data.MediaUrl
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URLEncoder
import java.util.Locale

/**
 * 随便听听看看数据层：
 * - 听：Radio Browser（countrycode=CN / tag 检索），失败/离线回退 assets/casual_radio.json；
 * - 看：B 站热门（popular）与排行榜（ranking/v2，rid=0/3/1/4/36/160）；
 * - 播放地址：pagelist 取 cid → playurl 取 durl，固定带 Referer/UA，qn 降档重试一次；
 * - 文件缓存 + 熔断（同一接口 3 连败 → 10 分钟）。
 */
class CasualRepositoryImpl(context: Context) : CasualRepository {

    private val appContext = context.applicationContext
    private val cache = FileCache(File(appContext.cacheDir, "casual"))
    private val seed = CasualRadioSeed(appContext)
    private val breaker = CasualCircuitBreaker()

    override suspend fun radios(page: Int): Result<List<RadioStation>> {
        val safePage = page.coerceAtLeast(1)
        val key = "radio|$safePage"
        val cached = cache.read(key)
        if (cached != null && cache.isFresh(cached, RADIO_TTL_MS)) {
            return Result.Ok(parseRadios(cached.data))
        }
        if (breaker.isOpen(API_RADIO)) {
            return Result.Ok(cached?.let { parseRadios(it.data) } ?: seed.load())
        }
        val url = radioUrl(safePage)
        val network = Http.ioCall { Http.getText(url, radioHeaders()) }
        return when (network) {
            is Result.Ok -> {
                breaker.success(API_RADIO)
                cache.write(key, network.value)
                val online = parseRadios(network.value)
                if (safePage == 1) {
                    Result.Ok((seed.load() + online).distinctBy { it.id })
                } else {
                    Result.Ok(online)
                }
            }
            is Result.Err -> {
                breaker.failure(API_RADIO)
                if (cached != null) {
                    Result.Ok(parseRadios(cached.data))
                } else {
                    TvLog.w(TAG, "电台接口失败，使用内置精选：${network.error.msg}")
                    Result.Ok(seed.load())
                }
            }
        }
    }

    override suspend fun clips(tab: CasualTab, page: Int): Result<List<ShortClip>> {
        val safePage = page.coerceAtLeast(1)
        val key = "clips|${tab.name}|$safePage"
        val cached = cache.read(key)
        if (cached != null && cache.isFresh(cached, CLIPS_TTL_MS)) {
            return Result.Ok(parseClips(cached.data))
        }
        if (breaker.isOpen(API_CLIPS)) {
            return cached?.let { Result.Ok(parseClips(it.data)) }
                ?: Result.failure(ErrorCodes.NETWORK, "B 站接口暂不可用，请稍后再试")
        }
        val url = clipsUrl(tab, safePage)
        return when (val network = Http.ioCall { Http.getText(url, biliHeaders()) }) {
            is Result.Ok -> {
                breaker.success(API_CLIPS)
                cache.write(key, network.value)
                Result.Ok(parseClips(network.value))
            }
            is Result.Err -> {
                breaker.failure(API_CLIPS)
                cached?.let { Result.Ok(parseClips(it.data)) } ?: network
            }
        }
    }

    override suspend fun resolveClip(bvid: String): Result<MediaUrl> {
        if (bvid.isBlank()) return Result.failure(ErrorCodes.SOURCE_INVALID, "缺少 bvid")
        val key = "playurl|v2|$bvid"
        val cached = cache.read(key)
        if (cached != null && cache.isFresh(cached, PLAYURL_TTL_MS)) {
            val json = runCatching { JSONObject(cached.data) }.getOrNull()
            json?.optString("url")?.takeIf { it.startsWith("http") }?.let {
                return Result.Ok(MediaUrl(label = bvid, url = it, headers = PLAY_HEADERS))
            }
        }
        if (breaker.isOpen(API_RESOLVE)) {
            return Result.failure(ErrorCodes.NETWORK, "B 站解析暂不可用，请稍后再试")
        }

        val cidResult = Http.ioCall {
            val text = Http.getText(PAGELIST_URL + URLEncoder.encode(bvid, "UTF-8"), biliHeaders())
            parseCid(text)
        }
        val cid = when (cidResult) {
            is Result.Ok -> cidResult.value
            is Result.Err -> {
                breaker.failure(API_RESOLVE)
                return cidResult
            }
        }

        var lastError: AppError? = null
        // X4-50(MStar) 真机 >720p 纹理路径黑屏：默认 qn=32，失败/黑屏再降 qn=16
        // （qn=64 会返回 1024p，首帧后无 texture 更新，见 t9 PLAYER_EVT/BLACK_SCREEN 日志）
        for (qn in listOf(32, 16)) {
            val attempt = Http.ioCall {
                val url = playurlUrl(bvid, cid, qn)
                val text = Http.getText(url, biliHeaders())
                parseDurl(text)
            }
            when (attempt) {
                is Result.Ok -> {
                    val mediaUrl = attempt.value
                    breaker.success(API_RESOLVE)
                    cache.write(key, JSONObject().put("url", mediaUrl).toString())
                    return Result.Ok(MediaUrl(label = bvid, url = mediaUrl, headers = PLAY_HEADERS))
                }
                is Result.Err -> {
                    lastError = attempt.error
                    TvLog.w(TAG, "playurl qn=$qn 失败：${attempt.error.msg}")
                }
            }
        }
        breaker.failure(API_RESOLVE)
        return Result.Err(lastError ?: AppError(ErrorCodes.NO_PLAY_URL, "没有可播放地址"))
    }

    // ---------------- 网络 / 解析 ----------------

    private fun playurlUrl(bvid: String, cid: Long, qn: Int): String =
        "$BILI_BASE/x/player/playurl?bvid=$bvid&cid=$cid&qn=$qn&fnval=16&platform=html5&high_quality=1"

    private fun radioUrl(page: Int): String {
        val tag = when (page) {
            2 -> "chillout"
            3 -> "jazz"
            4 -> "pop"
            else -> null
        }
        return if (tag != null) {
            "$RADIO_BASE/search?tag=$tag&limit=50&hidebroken=true&order=clickcount&reverse=true"
        } else {
            val offset = ((page.coerceAtLeast(1) - 1) * 50)
            "$RADIO_BASE/search?countrycode=CN&limit=50&hidebroken=true&order=clickcount&reverse=true&offset=$offset"
        }
    }

    private fun clipsUrl(tab: CasualTab, page: Int): String = when (tab) {
        CasualTab.RECOMMEND -> "$BILI_BASE/x/web-interface/popular?ps=20&pn=$page"
        else -> "$BILI_BASE/x/web-interface/ranking/v2?rid=${tab.rid}&type=all"
    }

    private fun parseRadios(text: String): List<RadioStation> = try {
        val root = JSONArray(text)
        val list = ArrayList<RadioStation>(root.length())
        for (i in 0 until root.length()) {
            val obj = root.optJSONObject(i) ?: continue
            val name = obj.optString("name").trim()
            val urls = buildList {
                obj.optString("url_resolved").takeIf { it.startsWith("http") }?.let(::add)
                obj.optString("url").takeIf { it.startsWith("http") && it !in this }?.let(::add)
            }
            if (name.isEmpty() || urls.isEmpty()) continue
            list.add(
                RadioStation(
                    id = obj.optString("stationuuid").ifBlank { name },
                    name = name,
                    tag = obj.optString("tags").replace(',', '/').truncate(48),
                    codec = obj.optString("codec").ifBlank { "UNKNOWN" },
                    bitrateKbps = obj.optInt("bitrate", 0),
                    urls = urls,
                    logo = obj.optString("favicon").takeIf { it.startsWith("http") },
                    group = obj.optString("country").ifBlank { "在线电台" },
                ),
            )
        }
        list
    } catch (t: Throwable) {
        TvLog.e(TAG, "电台解析失败", t)
        emptyList()
    }

    private fun parseClips(text: String): List<ShortClip> = try {
        val root = JSONObject(text)
        if (root.optInt("code", -1) != 0) throw IllegalStateException(root.optString("message", "code!=0"))
        val data = root.optJSONObject("data") ?: JSONObject()
        val array = data.optJSONArray("list") ?: JSONArray()
        val list = ArrayList<ShortClip>(array.length())
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            val bvid = obj.optString("bvid").trim()
            val title = obj.optString("title").trim()
            if (bvid.isEmpty() || title.isEmpty()) continue
            list.add(
                ShortClip(
                    bvid = bvid,
                    title = title,
                    pic = obj.optString("pic").replace("http://", "https://"),
                    durationSec = obj.optInt("duration", 0),
                    owner = obj.optJSONObject("owner")?.optString("name").orEmpty(),
                    views = obj.optJSONObject("stat")?.optLong("view", 0L) ?: 0L,
                    aid = obj.optLong("aid", 0L),
                ),
            )
        }
        list
    } catch (t: Throwable) {
        TvLog.e(TAG, "榜单解析失败", t)
        emptyList()
    }

    private fun parseCid(text: String): Long {
        val root = JSONObject(text)
        if (root.optInt("code", -1) != 0) throw IllegalStateException(root.optString("message", "pagelist failed"))
        val list = root.optJSONArray("data") ?: throw IllegalStateException("pagelist 无 data")
        if (list.length() == 0) throw IllegalStateException("pagelist 为空")
        return list.optJSONObject(0)?.optLong("cid", 0L) ?: 0L
    }

    private fun parseDurl(text: String): String {
        val root = JSONObject(text)
        if (root.optInt("code", -1) != 0) throw IllegalStateException(root.optString("message", "playurl failed"))
        val data = root.optJSONObject("data") ?: throw IllegalStateException("playurl 无 data")
        val durl = data.optJSONArray("durl") ?: throw IllegalStateException("playurl 无 durl")
        if (durl.length() == 0) throw IllegalStateException("playurl durl 为空")
        return durl.optJSONObject(0)?.optString("url").orEmpty().ifBlank {
            throw IllegalStateException("durl url 为空")
        }
    }

    private fun radioHeaders(): Map<String, String> = mapOf(
        "User-Agent" to BROWSER_UA,
        "Accept" to "application/json",
    )

    private fun biliHeaders(): Map<String, String> = BILI_HEADERS

    private fun formatViews(views: Long): String = when {
        views >= 100_000_000 -> String.format(Locale.ROOT, "%.1f亿", views / 100_000_000.0)
        views >= 10_000 -> String.format(Locale.ROOT, "%.1f万", views / 10_000.0)
        else -> views.toString()
    }

    private companion object {
        const val TAG = "CasualRepo"
        const val RADIO_BASE = "https://de1.api.radio-browser.info/json/stations"
        const val BILI_BASE = "https://api.bilibili.com"
        const val PAGELIST_URL = "$BILI_BASE/x/player/pagelist?bvid="
        const val BROWSER_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Safari/537.36"
        val BILI_HEADERS = mapOf(
            "User-Agent" to BROWSER_UA,
            "Referer" to "https://www.bilibili.com/",
            "Origin" to "https://www.bilibili.com",
            "Accept" to "application/json, text/plain, */*",
        )
        val PLAY_HEADERS = mapOf(
            "User-Agent" to BROWSER_UA,
            "Referer" to "https://www.bilibili.com/",
        )
        const val RADIO_TTL_MS = 6 * 60 * 60 * 1000L
        const val CLIPS_TTL_MS = 10 * 60 * 1000L
        const val PLAYURL_TTL_MS = 10 * 60 * 1000L
        const val API_RADIO = "radio"
        const val API_CLIPS = "clips"
        const val API_RESOLVE = "resolve"
    }
}

/** 简单熔断：同一接口连续 3 次失败 → 10 分钟不再请求。 */
class CasualCircuitBreaker {
    private val failures = HashMap<String, Int>()
    private val openUntil = HashMap<String, Long>()

    fun isOpen(key: String): Boolean {
        val until = openUntil[key] ?: return false
        if (System.currentTimeMillis() > until) {
            openUntil.remove(key)
            failures.remove(key)
            return false
        }
        return true
    }

    fun success(key: String) {
        failures.remove(key)
        openUntil.remove(key)
    }

    fun failure(key: String) {
        val count = (failures[key] ?: 0) + 1
        failures[key] = count
        if (count >= 3) openUntil[key] = System.currentTimeMillis() + OPEN_MS
    }

    private companion object {
        const val OPEN_MS = 10 * 60 * 1000L
    }
}

private fun String.truncate(max: Int): String = if (length <= max) this else substring(0, max)
