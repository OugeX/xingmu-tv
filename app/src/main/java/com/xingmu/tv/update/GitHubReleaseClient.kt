package com.xingmu.tv.update

import com.xingmu.tv.core.AppDispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/** 拉取 Release 的抽象：单测注入 fake，生产用 [GitHubReleaseClient]。 */
interface ReleaseFetcher {
    suspend fun latest(includePrerelease: Boolean): ReleaseInfo?
}

/**
 * GitHub Release 客户端（UPDATE_SPEC §1）。
 *
 * 超时：连接 3s / 读取 4s；任何失败都抛异常，由 [UpdateCenter] 统一静默处理。
 * 不引入 BuildConfig（`buildFeatures.buildConfig = false`），UA 由构造参数传入。
 */
class GitHubReleaseClient(
    private val userAgent: String = "XingmuTV",
    private val http: OkHttpClient = defaultClient(),
    private val io: kotlinx.coroutines.CoroutineDispatcher = AppDispatchers.Io,
) : ReleaseFetcher {

    override suspend fun latest(includePrerelease: Boolean): ReleaseInfo? = withContext(io) {
        val url = if (includePrerelease) {
            "$API_BASE/releases?per_page=10"
        } else {
            "$API_BASE/releases/latest"
        }
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", userAgent)
            .get()
            .build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            val body = response.body?.string().orEmpty()
            if (body.isBlank()) throw IOException("empty body")
            if (!includePrerelease) {
                GitHubReleaseParser.parse(body) ?: throw IOException("parse failed")
            } else {
                val array = runCatching { JSONArray(body) }.getOrNull() ?: throw IOException("parse failed")
                firstUsable(array) ?: throw IOException("no release")
            }
        }
    }

    private fun firstUsable(array: JSONArray): ReleaseInfo? {
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            if (obj.optBoolean("draft", false)) continue
            GitHubReleaseParser.parse(obj)?.let { return it }
        }
        return null
    }

    private companion object {
        const val API_BASE = "https://api.github.com/repos/OugeX/xingmu-tv"

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(3, TimeUnit.SECONDS)
            .readTimeout(4, TimeUnit.SECONDS)
            .callTimeout(10, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)
            .build()
    }
}

/** Release JSON 解析（org.json；单测直接用字符串验证）。 */
object GitHubReleaseParser {

    fun parse(json: String): ReleaseInfo? = runCatching { parse(JSONObject(json)) }.getOrNull()

    fun parse(obj: JSONObject): ReleaseInfo? {
        val tag = obj.optString("tag_name").trim()
        if (tag.isEmpty()) return null
        val notes = obj.optString("body").orEmpty()
        val pageUrl = obj.optString("html_url").trim()
        val assetsArray = obj.optJSONArray("assets") ?: JSONArray()
        val assets = buildList {
            for (i in 0 until assetsArray.length()) {
                val a = assetsArray.optJSONObject(i) ?: continue
                val name = a.optString("name").trim()
                val url = a.optString("browser_download_url").trim()
                if (name.isEmpty() || url.isEmpty()) continue
                add(
                    ReleaseAsset(
                        name = name,
                        sizeBytes = a.optLong("size", 0L),
                        downloadUrl = url,
                    ),
                )
            }
        }
        return ReleaseInfo(
            tag = tag,
            version = UpdateVersion.core(tag),
            notes = notes,
            pageUrl = pageUrl.ifBlank { "https://github.com/OugeX/xingmu-tv/releases" },
            prerelease = obj.optBoolean("prerelease", false),
            assets = assets,
        )
    }
}

/** 更新说明展示：去掉代码块/Markdown 记号，截前 500 字（UPDATE_SPEC §3）。 */
object ReleaseNotes {

    const val LIMIT = 500

    fun plain(body: String?): String {
        if (body.isNullOrBlank()) return ""
        var text = body
        text = text.replace(Regex("(?s)```.*?```"), " ")
        text = text.replace(Regex("(?s)~~~.*?~~~"), " ")
        text = text.replace(Regex("!\\[[^\\]]*]\\([^)]*\\)"), " ")
        text = text.replace(Regex("\\[([^\\]]+)]\\([^)]*\\)"), "$1")
        text = text.replace(Regex("(?m)^\\s{0,3}#{1,6}\\s*"), "")
        text = text.replace(Regex("(?m)^\\s*[-*+]\\s+"), "· ")
        text = text.replace(Regex("(?m)^\\s*>\\s?"), "")
        text = text.replace(Regex("<[^>]+>"), " ")
        text = text.replace(Regex("\\s+"), " ").trim()
        return if (text.length <= LIMIT) text else text.take(LIMIT) + "…"
    }
}
