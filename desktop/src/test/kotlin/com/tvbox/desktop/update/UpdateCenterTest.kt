package com.tvbox.desktop.update

import com.tvbox.desktop.data.Http
import com.tvbox.desktop.data.HttpException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

class UpdateCenterTest {

    private val dir: Path = Files.createTempDirectory("t36-update")
    private var lastCheck = 0L
    private var checkedAt = 0L

    @AfterTest
    fun cleanup() {
        dir.toFile().deleteRecursively()
    }

    private fun asset(name: String) = ReleaseAsset(name, 1000, "https://github.com/x/$name")

    private fun release(tag: String, assets: List<ReleaseAsset>) = ReleaseInfo(
        tag = tag,
        version = DesktopVersion.core(tag),
        notes = "",
        pageUrl = "https://github.com/OugeX/xingmu-tv/releases/tag/$tag",
        prerelease = false,
        assets = assets,
    )

    private fun center(
        fetcher: ReleaseFetcher,
        currentVersion: String = "0.2.0",
        windows: Boolean = true,
    ) = UpdateCenter(
        fetcher = fetcher,
        http = Http(),
        updateDir = dir.resolve("update"),
        currentVersion = { currentVersion },
        windows = { windows },
        lastCheckAt = { lastCheck },
        onCheckedAt = {
            lastCheck = it
            checkedAt = it
        },
        autoCheckEnabled = { true },
        scope = CoroutineScope(Dispatchers.Unconfined),
        now = { 1_000_000L },
    )

    @Test
    fun `network error is silent and returns Idle without events`() = runBlocking {
        val center = center(object : ReleaseFetcher {
            override suspend fun latest(includePrerelease: Boolean): ReleaseInfo? = throw IOException("offline")
        })
        val result = center.checkNow(manual = true)
        assertTrue(result is UpdateCheckResult.Failed)
        assertEquals(UpdateState.Idle, center.state)
        assertTrue(center.eventsSnapshot().isEmpty(), "失败不能产生 UI 事件")
        assertEquals(1_000_000L, lastCheck, "失败也记录上次检查时间，避免自动重试轰炸")
    }

    @Test
    fun `timeout is silent and returns Idle`() = runBlocking {
        val center = center(object : ReleaseFetcher {
            override suspend fun latest(includePrerelease: Boolean): ReleaseInfo? =
                throw HttpException("TIMEOUT", "请求超时")
        })
        center.checkNow(manual = true)
        assertEquals(UpdateState.Idle, center.state)
        assertTrue(center.eventsSnapshot().isEmpty())
    }

    @Test
    fun `all apk assets falls back to release page when tag newer`() = runBlocking {
        val center = center(object : ReleaseFetcher {
            override suspend fun latest(includePrerelease: Boolean): ReleaseInfo? =
                release("v0.3.4", listOf(asset("xingmu-tv-v0.3.4.apk")))
        })
        val result = center.checkNow(manual = true)
        val available = center.state as UpdateState.Available
        assertTrue(result is UpdateCheckResult.Available)
        assertTrue(available.candidate.pageOnly)
        assertEquals("v0.3.4", available.candidate.release.tag)
        assertEquals(1, center.eventsSnapshot().size)
    }

    @Test
    fun `asset version equal to current is UpToDate even if tag newer`() = runBlocking {
        val center = center(object : ReleaseFetcher {
            override suspend fun latest(includePrerelease: Boolean): ReleaseInfo? =
                release("v0.3.4", listOf(asset("XingmuTV-0.2.0.msi"), asset("XingmuTV-0.1.0.exe")))
        })
        center.checkNow(manual = true)
        assertTrue(center.state is UpdateState.UpToDate)
        assertTrue(center.eventsSnapshot().single() is UpdateUiEvent.UpToDate)
    }

    @Test
    fun `older desktop asset does not trigger update even if tag newer`() = runBlocking {
        val center = center(object : ReleaseFetcher {
            override suspend fun latest(includePrerelease: Boolean): ReleaseInfo? =
                release("v0.3.4", listOf(asset("XingmuTV-0.1.0.msi")))
        })
        center.checkNow(manual = true)
        assertTrue(center.state is UpdateState.UpToDate)
    }

    @Test
    fun `newer msi asset is selected on windows`() = runBlocking {
        val center = center(object : ReleaseFetcher {
            override suspend fun latest(includePrerelease: Boolean): ReleaseInfo? =
                release("v0.3.4", listOf(asset("XingmuTV-0.2.1.exe"), asset("XingmuTV-0.2.1.msi")))
        }, windows = true)
        center.checkNow(manual = true)
        val available = center.state as UpdateState.Available
        assertEquals("0.2.1", available.candidate.displayVersion)
        assertEquals(AssetKind.MSI, available.candidate.desktopAsset?.kind)
    }

    @Test
    fun `newer exe asset is selected on non windows`() = runBlocking {
        val center = center(object : ReleaseFetcher {
            override suspend fun latest(includePrerelease: Boolean): ReleaseInfo? =
                release("v0.3.4", listOf(asset("XingmuTV-0.2.1.msi"), asset("XingmuTV-0.2.1.exe")))
        }, windows = false)
        center.checkNow(manual = true)
        val available = center.state as UpdateState.Available
        assertEquals(AssetKind.EXE, available.candidate.desktopAsset?.kind)
    }

    @Test
    fun `no desktop assets and older tag stays Idle`() = runBlocking {
        val center = center(object : ReleaseFetcher {
            override suspend fun latest(includePrerelease: Boolean): ReleaseInfo? =
                release("v0.2.0", emptyList())
        })
        center.checkNow(manual = true)
        assertEquals(UpdateState.Idle, center.state)
        assertTrue(center.eventsSnapshot().isEmpty())
    }

    @Test
    fun `auto check is throttled by last check time`() = runBlocking {
        lastCheck = 0L
        var calls = 0
        val center = center(object : ReleaseFetcher {
            override suspend fun latest(includePrerelease: Boolean): ReleaseInfo? {
                calls++
                return release("v0.3.4", listOf(asset("XingmuTV-0.2.1.msi")))
            }
        })
        center.checkNow(manual = false)
        val second = center.checkNow(manual = false)
        assertTrue(second is UpdateCheckResult.Skipped)
        assertEquals(1, calls)
    }
}
