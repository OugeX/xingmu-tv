package com.xingmu.tv.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * 预置详情包的 SQLite 存储（t27）。
 *
 * 表结构（docs/PERF_ROADMAP.md「预置详情包」）：
 *   detail_seed(vod_id TEXT PRIMARY KEY, source_id TEXT, name TEXT, year TEXT,
 *               detail_json TEXT, fetched_at INTEGER)
 *
 * 设计要点：
 * - 只存原始 JSON 字符串（约 5–6KB/条，500 条约 2.7MB），字段解析复用 [MacCmsParser]，
 *   避免预置包格式和在线接口各写一套解析。
 * - 导入必须在一个事务里完成（500 条 ≤1s，低端盒子 SQLite 单独提交会 5–10 倍慢）。
 * - 数据库门面做成 [SeedDatabase] 接口：线上用 SQLiteOpenHelper，单测用 JDBC 跑同一套 SQL。
 */

/** 一条预置详情（与包内 entries 对齐）。 */
data class SeedEntry(
    val vodId: String,
    val sourceId: String,
    val name: String,
    val year: String,
    val fetchedAtMs: Long,
    val detailJson: String,
)

/** 预置库状态：设置页展示 + 版本去重。 */
data class SeedStats(
    val count: Int,
    val version: String?,
    val importedAtMs: Long,
    val sourceId: String?,
)

data class SeedHit(
    val entry: SeedEntry,
    val ageMs: Long,
)

/** 可注入的数据库门面（Android 实现见 [AndroidSeedDatabase]，单测实现见 app/src/test）。 */
interface SeedDatabase {
    fun <T> transaction(block: (SeedSession) -> T): T
    fun <T> read(block: (SeedSession) -> T): T
}

/** 事务/只读会话：只暴露预置库需要的三种操作，保证单测实现足够简单。 */
interface SeedSession {
    fun exec(sql: String, args: List<Any?> = emptyList())
    fun queryRow(sql: String, args: List<Any?> = emptyList(), columns: Int): Array<String?>?
    fun queryLong(sql: String, args: List<Any?> = emptyList()): Long?
}

object DetailSeedSchema {
    const val DB_NAME = "detail_seed.db"
    const val VERSION = 1

    const val TABLE = "detail_seed"
    const val META = "seed_meta"

    const val CREATE_TABLE = "CREATE TABLE IF NOT EXISTS $TABLE (" +
        "vod_id TEXT PRIMARY KEY NOT NULL, " +
        "source_id TEXT NOT NULL, " +
        "name TEXT, " +
        "year TEXT, " +
        "detail_json TEXT NOT NULL, " +
        "fetched_at INTEGER NOT NULL)"

    const val CREATE_META = "CREATE TABLE IF NOT EXISTS $META (" +
        "key TEXT PRIMARY KEY NOT NULL, value TEXT)"

    const val INSERT = "INSERT OR REPLACE INTO $TABLE " +
        "(vod_id, source_id, name, year, detail_json, fetched_at) VALUES (?, ?, ?, ?, ?, ?)"

    const val UPSERT_ONE = INSERT

    const val SELECT_BY_ID = "SELECT vod_id, source_id, name, year, detail_json, fetched_at " +
        "FROM $TABLE WHERE vod_id = ? AND source_id = ? LIMIT 1"

    const val SELECT_STATS = "SELECT COUNT(*) FROM $TABLE"

    const val SELECT_META = "SELECT value FROM $META WHERE key = ?"

    const val PUT_META = "INSERT OR REPLACE INTO $META (key, value) VALUES (?, ?)"

    const val KEY_VERSION = "seed_version"
    const val KEY_IMPORTED_AT = "imported_at"
    const val KEY_SOURCE = "seed_source"
}

/**
 * 预置详情读写。所有操作都在 [SeedDatabase] 的事务/只读会话里完成，
 * 线程安全由调用方的单线程 IO 协程保证（见 [DetailSeedSync] / [LocalSourceRepository]）。
 */
class DetailSeedStore(private val db: SeedDatabase) {

    /** 一次性导入；返回耗时（ms），供日志/验收对比「500 条 ≤1s」预算。 */
    fun import(entries: List<SeedEntry>, version: String, sourceId: String): Long {
        val started = System.currentTimeMillis()
        db.transaction { session ->
            entries.forEach { entry ->
                session.exec(
                    DetailSeedSchema.INSERT,
                    listOf(
                        entry.vodId,
                        entry.sourceId,
                        entry.name,
                        entry.year,
                        entry.detailJson,
                        entry.fetchedAtMs,
                    ),
                )
            }
            session.exec(DetailSeedSchema.PUT_META, listOf(DetailSeedSchema.KEY_VERSION, version))
            session.exec(
                DetailSeedSchema.PUT_META,
                listOf(DetailSeedSchema.KEY_IMPORTED_AT, System.currentTimeMillis().toString()),
            )
            session.exec(DetailSeedSchema.PUT_META, listOf(DetailSeedSchema.KEY_SOURCE, sourceId))
        }
        return System.currentTimeMillis() - started
    }

    /** 单条覆盖（7 天过期后的静默刷新用）。 */
    fun upsert(entry: SeedEntry) {
        db.transaction { session ->
            session.exec(
                DetailSeedSchema.UPSERT_ONE,
                listOf(entry.vodId, entry.sourceId, entry.name, entry.year, entry.detailJson, entry.fetchedAtMs),
            )
        }
    }

    fun find(vodId: String, sourceId: String): SeedHit? = db.read { session ->
        val row = session.queryRow(DetailSeedSchema.SELECT_BY_ID, listOf(vodId, sourceId), 6) ?: return@read null
        val entry = SeedEntry(
            vodId = row[0].orEmpty(),
            sourceId = row[1].orEmpty(),
            name = row[2].orEmpty(),
            year = row[3].orEmpty(),
            detailJson = row[4].orEmpty(),
            fetchedAtMs = row[5]?.toLongOrNull() ?: 0L,
        )
        SeedHit(entry = entry, ageMs = (System.currentTimeMillis() - entry.fetchedAtMs).coerceAtLeast(0L))
    }

    fun stats(): SeedStats = db.read { session ->
        SeedStats(
            count = session.queryLong(DetailSeedSchema.SELECT_STATS)?.toInt() ?: 0,
            version = session.queryRow(DetailSeedSchema.SELECT_META, listOf(DetailSeedSchema.KEY_VERSION), 1)?.get(0),
            importedAtMs = session.queryRow(DetailSeedSchema.SELECT_META, listOf(DetailSeedSchema.KEY_IMPORTED_AT), 1)
                ?.get(0)?.toLongOrNull() ?: 0L,
            sourceId = session.queryRow(DetailSeedSchema.SELECT_META, listOf(DetailSeedSchema.KEY_SOURCE), 1)?.get(0),
        )
    }
}

/** Android 侧实现：SQLiteOpenHelper + 单事务批量插入 + prepared statement 复用。 */
class AndroidSeedDatabase(context: Context) : SeedDatabase {

    private val helper = object : SQLiteOpenHelper(
        context.applicationContext,
        DetailSeedSchema.DB_NAME,
        null,
        DetailSeedSchema.VERSION,
    ) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(DetailSeedSchema.CREATE_TABLE)
            db.execSQL(DetailSeedSchema.CREATE_META)
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            // 预置库是纯派生数据：版本升级直接重建，不迁移。
            db.execSQL("DROP TABLE IF EXISTS ${DetailSeedSchema.TABLE}")
            db.execSQL("DROP TABLE IF EXISTS ${DetailSeedSchema.META}")
            onCreate(db)
        }
    }

    override fun <T> transaction(block: (SeedSession) -> T): T {
        val db = helper.writableDatabase
        db.beginTransaction()
        return try {
            val result = block(SqliteSeedSession(db))
            db.setTransactionSuccessful()
            result
        } finally {
            db.endTransaction()
        }
    }

    override fun <T> read(block: (SeedSession) -> T): T = block(SqliteSeedSession(helper.readableDatabase))

    private class SqliteSeedSession(private val db: SQLiteDatabase) : SeedSession {

        override fun exec(sql: String, args: List<Any?>) {
            db.execSQL(sql, args.toTypedArray())
        }

        override fun queryRow(sql: String, args: List<Any?>, columns: Int): Array<String?>? {
            db.rawQuery(sql, args.map { it?.toString() }.toTypedArray()).use { cursor ->
                if (!cursor.moveToFirst()) return null
                return Array(columns) { index ->
                    if (cursor.isNull(index)) null else cursor.getString(index)
                }
            }
        }

        override fun queryLong(sql: String, args: List<Any?>): Long? {
            db.rawQuery(sql, args.map { it?.toString() }.toTypedArray()).use { cursor ->
                return if (cursor.moveToFirst()) cursor.getLong(0) else null
            }
        }
    }
}
