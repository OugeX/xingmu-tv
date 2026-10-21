package com.tvbox.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * t27：预置详情包解析 / 校验（真实产物：assets/detail_seed.sample.json + release/detail-seed-v1.zip）。
 */
class DetailSeedPackageTest {

    private val sampleFile = TestFiles.seedSample
    private val zipFile = TestFiles.seedZip

    @Test
    fun sampleParses20EntriesWithPlayUrls() {
        val pkg = SeedPackage.parse(sampleFile.readText(Charsets.UTF_8))
        assertEquals(20, pkg.count)
        assertEquals("bfzy1", pkg.sourceId)
        pkg.entries.forEach { entry ->
            assertTrue("vodId 不能为空", entry.vodId.isNotBlank())
            assertTrue("name 不能为空: ${entry.vodId}", entry.name.isNotBlank())
            assertTrue("detailJson 不能为空: ${entry.vodId}", entry.detailJson.length > 500)
            assertTrue("detailJson 缺少 vod_play_url: ${entry.vodId}", entry.detailJson.contains("vod_play_url"))
            assertTrue("fetchedAt 必须能解析", entry.fetchedAtMs > 0)
        }
    }

    @Test
    fun zipManifestMatchesSeedBytesAndSha256() {
        assertTrue("缺少产物 $zipFile", zipFile.exists())
        val content = SeedPackageFiles.readZip(zipFile)
        val manifest = content.manifest()
        assertEquals(500, manifest.count)
        assertEquals("bfzy1", manifest.sourceId)
        assertTrue("sha256 前缀校验", SeedPackageFiles.verify(manifest, content.seedJson))
        assertEquals(manifest.detailSeedBytes, content.seedJson.toByteArray(Charsets.UTF_8).size.toLong())
        assertEquals(manifest.detailSeedSha256, SeedPackageFiles.sha256Hex(content.seedJson))

        val pkg = content.seedPackage()
        assertEquals(500, pkg.count)
        assertTrue(pkg.entries.all { it.detailJson.contains("vod_play_url") })
    }

    @Test
    fun sha256PrefixMismatchRejected() {
        val good = SeedPackageFiles.sha256Hex("hello")
        assertTrue(SeedPackageFiles.sha256PrefixOk(good, good, 16))
        assertFalse(SeedPackageFiles.sha256PrefixOk(good, "0123456789abcdef", 16))
        assertFalse("expected 太短必须拒绝", SeedPackageFiles.sha256PrefixOk(good, "abc", 16))
    }

    @Test
    fun isoTimeParsesBeijingOffset() {
        // 2026-10-22T05:51:57+08:00 == 2026-10-21T21:51:57Z
        val parsed = SeedTime.parseIsoMs("2026-10-22T05:51:57+08:00")
        assertEquals(1_792_619_517_000L, parsed)
        assertEquals(1_792_619_517_000L, SeedTime.parseIsoMs("2026-10-21T21:51:57Z"))
        assertTrue(SeedTime.parseIsoMs("") == null)
    }
}
