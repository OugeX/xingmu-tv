package com.tvbox.desktop.update

import com.tvbox.desktop.BuildInfo
import com.tvbox.desktop.data.Http
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

/** 拉取 Release 的抽象；单测注入 fake，生产用 [ReleaseClient]。 */
interface ReleaseFetcher {
    suspend fun latest(includePrerelease: Boolean): ReleaseInfo?
}

/**
 * GitHub Release 客户端（桌面端）：
 * - `/releases/latest`；可选 `/releases?per_page=10` 取测试版；
 * - Header `Accept: application/vnd.github+json`、`User-Agent: XingmuTV-desktop/<ver>`；
 * - 连接 3s / 请求（读）4s；任何失败抛异常，由 UpdateCenter 静默处理。
 */
class ReleaseClient(
    userAgentVersion: String = BuildInfo.version,
    private val http: Http = Http(defaultReferer = null, connectTimeoutMs = 3_000L),
) : ReleaseFetcher {

    private val userAgent = "XingmuTV-desktop/$userAgentVersion"

    override suspend fun latest(includePrerelease: Boolean): ReleaseInfo? {
        val url = if (includePrerelease) "$API_BASE/releases?per_page=10" else "$API_BASE/releases/latest"
        val text = http.getText(
            url = url,
            headers = mapOf(
                "Accept" to "application/vnd.github+json",
                "User-Agent" to userAgent,
            ),
            timeoutMs = READ_TIMEOUT_MS,
            maxBytes = 2 * 1024 * 1024,
        )
        if (!includePrerelease) {
            return ReleaseParser.parse(text) ?: throw IOException("Release JSON 解析失败")
        }
        val array = runCatching { JSONArray(text) }.getOrNull() ?: throw IOException("Release 列表解析失败")
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            if (obj.optBoolean("draft", false)) continue
            ReleaseParser.parse(obj)?.let { return it }
        }
        throw IOException("没有可用 Release")
    }

    private companion object {
        const val API_BASE = "https://api.github.com/repos/OugeX/xingmu-tv"
        const val READ_TIMEOUT_MS = 4_000
    }
}

object ReleaseParser {

    fun parse(json: String): ReleaseInfo? = runCatching { parse(JSONObject(json)) }.getOrNull()

    fun parse(obj: JSONObject): ReleaseInfo? {
        val tag = obj.optString("tag_name").trim()
        if (tag.isEmpty()) return null
        val assetsArray = obj.optJSONArray("assets") ?: JSONArray()
        val assets = buildList {
            for (i in 0 until assetsArray.length()) {
                val asset = assetsArray.optJSONObject(i) ?: continue
                val name = asset.optString("name").trim()
                val url = asset.optString("browser_download_url").trim()
                if (name.isEmpty() || url.isEmpty()) continue
                add(
                    ReleaseAsset(
                        name = name,
                        sizeBytes = asset.optLong("size", 0L),
                        downloadUrl = url,
                    ),
                )
            }
        }
        return ReleaseInfo(
            tag = tag,
            version = DesktopVersion.core(tag),
            notes = obj.optString("body").orEmpty(),
            pageUrl = obj.optString("html_url").trim()
                .ifBlank { "https://github.com/OugeX/xingmu-tv/releases" },
            prerelease = obj.optBoolean("prerelease", false),
            assets = assets,
        )
    }
}
