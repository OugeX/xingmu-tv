package com.tvbox.app.data

import java.sql.Connection
import java.sql.DriverManager

/**
 * 单测用 SQLite 门面：与 Android 侧 [AndroidSeedDatabase] 跑同一套 [DetailSeedSchema]，
 * 但底层换成 sqlite-jdbc（JVM 上真实 SQLite 引擎）。设备关机时也能验证「500 条 ≤1s」预算。
 */
class JdbcSeedDatabase(private val connection: Connection = DriverManager.getConnection("jdbc:sqlite::memory:")) :
    SeedDatabase, AutoCloseable {

    init {
        connection.createStatement().use { statement ->
            statement.execute(DetailSeedSchema.CREATE_TABLE)
            statement.execute(DetailSeedSchema.CREATE_META)
        }
    }

    override fun <T> transaction(block: (SeedSession) -> T): T {
        connection.autoCommit = false
        return try {
            val result = block(Session())
            connection.commit()
            result
        } catch (t: Throwable) {
            runCatching { connection.rollback() }
            throw t
        } finally {
            connection.autoCommit = true
        }
    }

    override fun <T> read(block: (SeedSession) -> T): T = block(Session())

    override fun close() {
        connection.close()
    }

    private inner class Session : SeedSession {

        override fun exec(sql: String, args: List<Any?>) {
            connection.prepareStatement(sql).use { ps ->
                args.forEachIndexed { index, value -> ps.setObject(index + 1, value) }
                ps.executeUpdate()
            }
        }

        override fun queryRow(sql: String, args: List<Any?>, columns: Int): Array<String?>? {
            connection.prepareStatement(sql).use { ps ->
                args.forEachIndexed { index, value -> ps.setObject(index + 1, value) }
                ps.executeQuery().use { rs ->
                    if (!rs.next()) return null
                    return Array(columns) { index -> rs.getString(index + 1) }
                }
            }
        }

        override fun queryLong(sql: String, args: List<Any?>): Long? {
            connection.prepareStatement(sql).use { ps ->
                args.forEachIndexed { index, value -> ps.setObject(index + 1, value) }
                ps.executeQuery().use { rs ->
                    return if (rs.next()) rs.getLong(1) else null
                }
            }
        }
    }
}
