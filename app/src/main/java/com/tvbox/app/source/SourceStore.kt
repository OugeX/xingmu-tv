package com.tvbox.app.source

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.tvbox.app.core.AppDispatchers
import com.tvbox.app.core.TvLog
import com.tvbox.app.data.SourceKind
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 源表持久化（SQLite 直写，不用 Room：minSdk 21 + 不引入注解处理器，包体/构建都更省）。
 *
 * 表结构对齐 docs/PLAN_SOURCES.md §12（source 表；v1.1 字段全部在 config_json 里，无新增列）。
 * DB 独立命名 `tvbox_sources.db`，避免与后续 Room 库的 schema 版本互相牵制。
 */
data class StoredSource(
    val id: String,
    val kind: String,
    val name: String,
    val enabled: Boolean,
    val priority: Int,
    val configJson: String,
    val fingerprint: String,
    val userTouched: Boolean,
    val bundleId: String?,
    val createdAt: Long,
    val updatedAt: Long,
    val probeState: String,
    val probeAt: Long?,
    val failStreak: Int,
    val activeBaseUrl: String?,
) {
    /** config_json → 完整内存表示（宽松解析：已入库的配置不再做 allowLan 等限制）。 */
    fun toParsedSource(): ParsedSource? {
        val result = SourceSpecParser.parseText(
            configJson,
            ParseOptions(maxBytes = 16 * 1024 * 1024, allowLan = true, allowInternalFields = true),
        )
        val parsed = result.accepted.firstOrNull()
        if (parsed == null) {
            TvLog.w("SourceStore", "config_json 解析失败 id=$id：${result.documentError?.message ?: result.rejected.firstOrNull()?.message}")
        }
        return parsed
    }
}

class SourceStore(context: Context) {

    private val appContext = context.applicationContext
    private val helper = object : SQLiteOpenHelper(appContext, DB_NAME, null, DB_VERSION) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE source (
                  id TEXT PRIMARY KEY,
                  kind TEXT NOT NULL,
                  name TEXT NOT NULL,
                  enabled INTEGER NOT NULL DEFAULT 1,
                  priority INTEGER NOT NULL DEFAULT 100,
                  config_json TEXT NOT NULL,
                  fingerprint TEXT NOT NULL,
                  user_touched INTEGER NOT NULL DEFAULT 0,
                  bundle_id TEXT,
                  created_at INTEGER NOT NULL,
                  updated_at INTEGER NOT NULL,
                  probe_state TEXT NOT NULL DEFAULT 'UNVERIFIED',
                  probe_at INTEGER,
                  fail_streak INTEGER NOT NULL DEFAULT 0,
                  active_base_url TEXT
                )
                """.trimIndent(),
            )
            db.execSQL("CREATE UNIQUE INDEX idx_source_fp ON source(kind, fingerprint)")
            db.execSQL("CREATE TABLE meta (key TEXT PRIMARY KEY, value TEXT NOT NULL)")
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            // v1 只有一张表；未来版本在此追加 ALTER TABLE（PLAN_SOURCES §12 迁移约定）。
        }
    }

    private val seeded = AtomicBoolean(false)

    // ---------------------------------------------------------------- 查询

    fun all(): List<StoredSource> = query(null, null)

    fun byId(id: String): StoredSource? = query("id = ?", arrayOf(id)).firstOrNull()

    fun byFingerprint(kind: String, fingerprint: String): StoredSource? =
        query("kind = ? AND fingerprint = ?", arrayOf(kind, fingerprint)).firstOrNull()

    fun isRemoved(id: String): Boolean = meta("removed.$id") == "1"

    private fun query(where: String?, args: Array<String>?): List<StoredSource> {
        val out = ArrayList<StoredSource>()
        try {
            helper.readableDatabase.query(
                "source", null, where, args, null, null, "priority ASC, id ASC",
            ).use { c ->
                while (c.moveToNext()) out.add(read(c))
            }
        } catch (t: Throwable) {
            TvLog.e(TAG, "query 失败", t)
        }
        return out
    }

    private fun read(c: Cursor): StoredSource = StoredSource(
        id = c.getString(c.getColumnIndexOrThrow("id")),
        kind = c.getString(c.getColumnIndexOrThrow("kind")),
        name = c.getString(c.getColumnIndexOrThrow("name")),
        enabled = c.getInt(c.getColumnIndexOrThrow("enabled")) != 0,
        priority = c.getInt(c.getColumnIndexOrThrow("priority")),
        configJson = c.getString(c.getColumnIndexOrThrow("config_json")),
        fingerprint = c.getString(c.getColumnIndexOrThrow("fingerprint")),
        userTouched = c.getInt(c.getColumnIndexOrThrow("user_touched")) != 0,
        bundleId = c.getString(c.getColumnIndexOrThrow("bundle_id")),
        createdAt = c.getLong(c.getColumnIndexOrThrow("created_at")),
        updatedAt = c.getLong(c.getColumnIndexOrThrow("updated_at")),
        probeState = c.getString(c.getColumnIndexOrThrow("probe_state")) ?: "UNVERIFIED",
        probeAt = c.getLongOrNull(c.getColumnIndexOrThrow("probe_at")),
        failStreak = c.getInt(c.getColumnIndexOrThrow("fail_streak")),
        activeBaseUrl = c.getString(c.getColumnIndexOrThrow("active_base_url")),
    )

    // ---------------------------------------------------------------- 写入

    data class UpsertOutcome(
        val inserted: Int,
        val updated: Int,
        val reused: List<Pair<String, String>>, // 新 id -> 复用的旧 id（端点指纹重复）
    )

    /** 导入写入：按 id 更新、按端点指纹复用旧 id（PLAN_SOURCES §5.1）。 */
    fun upsert(parsed: List<ParsedSource>, userTouched: Boolean): UpsertOutcome {
        var inserted = 0
        var updated = 0
        val reused = ArrayList<Pair<String, String>>()
        val db = helper.writableDatabase
        db.beginTransaction()
        try {
            val now = System.currentTimeMillis()
            for (src in parsed) {
                val fp = src.fingerprint()
                val existingById = byId(src.id)
                val existingByFp = if (fp.isEmpty()) null else byFingerprint(src.kind.name.lowercase(), fp)
                val target = existingById ?: existingByFp
                if (target != null) {
                    if (existingById == null && existingByFp != null) reused.add(src.id to existingByFp.id)
                    val values = ContentValues().apply {
                        put("name", src.name)
                        put("kind", src.kind.name.lowercase())
                        put("config_json", src.toJson().toString())
                        put("fingerprint", fp)
                        put("updated_at", now)
                        if (!target.userTouched) {
                            put("enabled", if (src.enabled) 1 else 0)
                            put("priority", src.priority)
                        }
                        if (userTouched) put("user_touched", 1)
                    }
                    db.update("source", values, "id = ?", arrayOf(target.id))
                    updated++
                } else {
                    val values = ContentValues().apply {
                        put("id", src.id)
                        put("kind", src.kind.name.lowercase())
                        put("name", src.name)
                        put("enabled", if (src.enabled) 1 else 0)
                        put("priority", src.priority)
                        put("config_json", src.toJson().toString())
                        put("fingerprint", fp)
                        put("user_touched", if (userTouched) 1 else 0)
                        put("bundle_id", src.bundleId)
                        put("created_at", now)
                        put("updated_at", now)
                        put("probe_state", "UNVERIFIED")
                        put("fail_streak", 0)
                    }
                    val rowId = db.insertWithOnConflict("source", null, values, SQLiteDatabase.CONFLICT_IGNORE)
                    if (rowId > 0) {
                        inserted++
                    } else {
                        TvLog.w(TAG, "插入被忽略（id=${src.id} 或端点指纹冲突）")
                    }
                }
                if (isRemoved(src.id)) metaRemove("removed.${src.id}")
            }
            db.setTransactionSuccessful()
        } catch (t: Throwable) {
            TvLog.e(TAG, "upsert 失败", t)
        } finally {
            db.endTransaction()
        }
        if (inserted + updated > 0) {
            TvLog.i(TAG, "upsert: inserted=$inserted updated=$updated reused=${reused.size}")
        }
        return UpsertOutcome(inserted, updated, reused)
    }

    fun setEnabled(id: String, enabled: Boolean): Boolean = update("id = ?", arrayOf(id)) {
        put("enabled", if (enabled) 1 else 0)
        put("user_touched", 1)
        put("updated_at", System.currentTimeMillis())
    }

    fun setPriority(id: String, priority: Int): Boolean = update("id = ?", arrayOf(id)) {
        put("priority", priority)
        put("user_touched", 1)
        put("updated_at", System.currentTimeMillis())
    }

    fun remove(id: String): Boolean {
        val db = helper.writableDatabase
        val n = db.delete("source", "id = ?", arrayOf(id))
        // 记住“已删除”，内置源版本升级时不复活
        metaPut("removed.$id", "1")
        return n > 0
    }

    fun updateProbe(id: String, state: String, failStreak: Int, activeBaseUrl: String?) = update("id = ?", arrayOf(id)) {
        put("probe_state", state)
        put("probe_at", System.currentTimeMillis())
        put("fail_streak", failStreak)
        if (activeBaseUrl != null) put("active_base_url", activeBaseUrl)
    }

    private fun update(where: String, args: Array<String>, block: ContentValues.() -> Unit): Boolean {
        return try {
            val values = ContentValues().apply(block)
            helper.writableDatabase.update("source", values, where, args) > 0
        } catch (t: Throwable) {
            TvLog.e(TAG, "update 失败", t)
            false
        }
    }

    // ---------------------------------------------------------------- 内置源装载

    /**
     * 首次启动装载 assets/sources.builtin.json（幂等：按 bundle version 记 meta，已装过直接返回）。
     * 用户删掉的源记入 removed.<id>，内置源升级也不会复活。
     */
    suspend fun seedBuiltin(): Int = withContext(AppDispatchers.IoLimited) {
        if (!seeded.compareAndSet(false, true)) return@withContext 0
        try {
            selfHealConfigs()
            val text = appContext.assets.open("sources.builtin.json").bufferedReader(Charsets.UTF_8).use { it.readText() }
            val result = SourceSpecParser.parseText(text, ParseOptions(maxBytes = 8 * 1024 * 1024))
            val bundle = result.bundle
            if (bundle == null) {
                TvLog.e(TAG, "内置源合集解析失败：${result.documentError?.message ?: result.rejected.firstOrNull()?.message}")
                return@withContext 0
            }
            val storedVersion = meta("builtin.version")?.toIntOrNull() ?: -1
            if (storedVersion >= bundle.version) {
                TvLog.d(TAG, "内置源已装载过 v$storedVersion，跳过")
                return@withContext 0
            }
            val missing = bundle.sources.filter { byId(it.id) == null && !isRemoved(it.id) }
            var count = 0
            if (missing.isNotEmpty()) {
                val db = helper.writableDatabase
                db.beginTransaction()
                try {
                    val now = System.currentTimeMillis()
                    for (src in missing) {
                        val values = ContentValues().apply {
                            put("id", src.id)
                            put("kind", src.kind.name.lowercase())
                            put("name", src.name)
                            put("enabled", if (src.enabled) 1 else 0)
                            put("priority", src.priority)
                            put("config_json", src.toJson().toString())
                            put("fingerprint", src.fingerprint())
                            put("user_touched", 0)
                            put("bundle_id", bundle.id)
                            put("created_at", now)
                            put("updated_at", now)
                            put("probe_state", "UNVERIFIED")
                        }
                        if (db.insertWithOnConflict("source", null, values, SQLiteDatabase.CONFLICT_IGNORE) > 0) count++
                    }
                    db.setTransactionSuccessful()
                } finally {
                    db.endTransaction()
                }
            }
            metaPut("builtin.version", bundle.version.toString())
            TvLog.i(TAG, "内置源装载完成 v${bundle.version}：新增 $count 条（共 ${bundle.sources.size}）")
            count
        } catch (t: Throwable) {
            seeded.set(false)
            TvLog.e(TAG, "内置源装载失败", t)
            0
        }
    }

    /**
     * 自愈：清理历史版本写入 config_json 的 `bundleId`（该字段已改为只存 DB 列）。
     * 只在装载时跑一次，避免老安装升级后“配置回读失败 → 0 源”。
     */
    private fun selfHealConfigs(): Int {
        var healed = 0
        for (stored in all()) {
            if (!stored.configJson.contains("\"bundleId\"")) continue
            val cleaned = try {
                org.json.JSONObject(stored.configJson).apply { remove("bundleId") }.toString()
            } catch (t: Throwable) {
                continue
            }
            if (update("id = ?", arrayOf(stored.id)) {
                    put("config_json", cleaned)
                    put("updated_at", System.currentTimeMillis())
                }
            ) {
                healed++
            }
        }
        if (healed > 0) TvLog.i(TAG, "config_json 自愈：移除遗留 bundleId 字段 $healed 条")
        return healed
    }

    // ---------------------------------------------------------------- meta
    fun meta(key: String): String? = try {
        helper.readableDatabase.query("meta", arrayOf("value"), "key = ?", arrayOf(key), null, null, null).use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    } catch (t: Throwable) {
        null
    }

    fun metaPut(key: String, value: String) {
        try {
            helper.writableDatabase.insertWithOnConflict(
                "meta", null,
                ContentValues().apply {
                    put("key", key)
                    put("value", value)
                },
                SQLiteDatabase.CONFLICT_REPLACE,
            )
        } catch (t: Throwable) {
            TvLog.w(TAG, "meta 写入失败 $key", t)
        }
    }

    private fun metaRemove(key: String) {
        try {
            helper.writableDatabase.delete("meta", "key = ?", arrayOf(key))
        } catch (_: Throwable) {
        }
    }

    fun close() {
        runCatching { helper.close() }
    }

    private companion object {
        const val TAG = "SourceStore"
        const val DB_NAME = "tvbox_sources.db"
        const val DB_VERSION = 1
    }
}

private fun Cursor.getLongOrNull(index: Int): Long? = if (isNull(index)) null else getLong(index)

/** 便捷：config_json 的 kind 转 [SourceKind]。 */
internal fun String.toSourceKindOrNull(): SourceKind? = when (this.lowercase()) {
    "maccms" -> SourceKind.MACCMS
    "m3u" -> SourceKind.M3U
    "bundle" -> SourceKind.BUNDLE
    else -> null
}
