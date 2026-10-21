package com.tvbox.desktop.data

import com.tvbox.desktop.core.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * 源仓库：内置合集（仓库 assets/sources.builtin.json）+ 用户导入（配置目录 sources.user.json）。
 *
 * - 内置文件只做相对引用，不在 desktop/ 复制第二份；打包运行时回退 jar 内 resources。
 * - 合并语义遵循 `tvapp.source.v1.1` 的 bundle merge=byId：同 id 时用户配置覆盖内置。
 */
class SourceStore(
    private val paths: AppPaths,
    private val http: Http,
) {
    data class BuiltinLoad(
        val sources: List<ImportedSource>,
        val issues: List<SourceIssue>,
        val source: String,
        val error: String? = null,
    )

    fun loadBuiltin(): BuiltinLoad {
        val (text, origin) = paths.readBundled("sources.builtin.json")
            ?: return BuiltinLoad(emptyList(), emptyList(), "缺失", "找不到内置源文件 sources.builtin.json（仓库 assets/ 或 jar 资源）")
        val report = SourceSpecParser.parseText(text, origin)
        if (report.issues.isNotEmpty()) {
            Log.w("Sources", "内置源校验警告 ${report.issues.size} 条，首个：${report.issues.first()}")
        }
        return BuiltinLoad(report.sources, report.issues, origin)
    }

    fun loadUser(): List<ImportedSource> {
        val file = paths.userSourcesFile
        if (!Files.isRegularFile(file)) return emptyList()
        return try {
            val doc = JSONObject(Files.readString(file))
            val array = doc.optJSONArray("sources") ?: return emptyList()
            val result = ArrayList<ImportedSource>(array.length())
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                val report = SourceSpecParser.parseDoc(obj)
                report.sources.firstOrNull()?.let { result += it }
            }
            result
        } catch (t: Throwable) {
            Log.w("Sources", "读取用户源失败：${t.message}")
            emptyList()
        }
    }

    suspend fun saveUser(sources: List<ImportedSource>) = withContext(Dispatchers.IO) {
        try {
            Files.createDirectories(paths.configDir)
            val doc = JSONObject()
                .put("schema", "tvapp.source.v1.1")
                .put("kind", "bundle")
                .put("id", "user-imported")
                .put("name", "用户导入")
                .put("version", 1)
                .put("merge", "byId")
                .put("sources", org.json.JSONArray().also { arr -> sources.forEach { arr.put(it.raw) } })
            val tmp: Path = paths.configDir.resolve("sources.user.json.tmp")
            Files.writeString(tmp, doc.toString(2))
            try {
                Files.move(tmp, paths.userSourcesFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: Exception) {
                Files.move(tmp, paths.userSourcesFile, StandardCopyOption.REPLACE_EXISTING)
            }
            Unit
        } catch (t: Throwable) {
            Log.e("Sources", "保存用户源失败", t)
        }
    }

    fun merge(builtin: List<ImportedSource>, user: List<ImportedSource>): List<SourceConfig> {
        val byId = LinkedHashMap<String, ImportedSource>()
        builtin.forEach { byId[it.config.id] = it }
        user.forEach { byId[it.config.id] = it }
        return byId.values
            .map { it.config }
            .sortedWith(compareBy({ it.priority }, { it.name }))
    }

    suspend fun fetchUrl(url: String): String = http.getText(url, emptyMap(), 20_000, 8 * 1024 * 1024)

    /** 生成用户可直接粘贴的导入模板，错误提示里用。 */
    fun exampleTemplate(): String = """
        {
          "schema": "tvapp.source.v1.1",
          "kind": "maccms",
          "id": "my-source",
          "name": "我的源",
          "baseUrl": "https://example.com/api.php/provide/vod/",
          "headers": { "Referer": "https://example.com/" }
        }
    """.trimIndent()
}
