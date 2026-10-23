package com.tvbox.desktop.update

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import java.nio.file.Files
import java.nio.file.Path

class UpdateDownloaderTest {

    private val dir: Path = Files.createTempDirectory("t36-download")

    @AfterTest
    fun cleanup() {
        dir.toFile().deleteRecursively()
    }

    @Test
    fun `msi magic is validated`() {
        val file = dir.resolve("a.msi")
        Files.write(file, byteArrayOf(0xD0.toByte(), 0xCF.toByte(), 0x11, 0xE0.toByte(), 0x00, 0x01))
        assertTrue(UpdateDownloader.validateHeader(file, AssetKind.MSI))
        assertFalse(UpdateDownloader.validateHeader(file, AssetKind.EXE))
    }

    @Test
    fun `exe magic is validated`() {
        val file = dir.resolve("a.exe")
        Files.write(file, byteArrayOf(0x4D, 0x5A, 0x00, 0x00))
        assertTrue(UpdateDownloader.validateHeader(file, AssetKind.EXE))
        assertFalse(UpdateDownloader.validateHeader(file, AssetKind.MSI))
    }

    @Test
    fun `short or missing files are rejected`() {
        val short = dir.resolve("short.msi")
        Files.write(short, byteArrayOf(0xD0.toByte(), 0xCF.toByte()))
        assertFalse(UpdateDownloader.validateHeader(short, AssetKind.MSI))
        assertFalse(UpdateDownloader.validateHeader(dir.resolve("missing.msi"), AssetKind.MSI))
    }

    @Test
    fun `content length validation`() {
        assertTrue(UpdateDownloader.contentLengthMatches(100, 100))
        assertTrue(UpdateDownloader.contentLengthMatches(100, -1), "服务端未提供 Content-Length 时跳过")
        assertFalse(UpdateDownloader.contentLengthMatches(99, 100))
    }

    @Test
    fun `file name is sanitized`() {
        assertTrue(UpdateDownloader.sanitizeFileName("../../XingmuTV-0.2.0.msi").endsWith("XingmuTV-0.2.0.msi"))
        assertTrue(UpdateDownloader.sanitizeFileName("a/b\\c d.exe").contains("c_d.exe"))
    }
}
