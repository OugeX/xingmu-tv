package com.xingmu.tv.update

import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * UPDATE_SPEC §1/§6：失败的检查必须**完全静默**（不改 UI 状态、不发 UI 事件），
 * 成功的检查才产生 Available / UpToDate。
 */
class UpdateCenterTest {

    private class FakePrefs(var stored: Long = 0L) : UpdatePreferences {
        override var lastCheckAt: Long
            get() = stored
            set(value) {
                stored = value
            }
    }

    private class FakeFetcher(private val block: () -> ReleaseInfo?) : ReleaseFetcher {
        var calls = 0
        override suspend fun latest(includePrerelease: Boolean): ReleaseInfo? {
            calls++
            return block()
        }
    }

    private fun release(
        version: String,
        withApk: Boolean = true,
    ): ReleaseInfo = ReleaseInfo(
        tag = "v$version",
        version = version,
        notes = "更新说明",
        pageUrl = "https://github.com/OugeX/xingmu-tv/releases/tag/v$version",
        prerelease = false,
        assets = if (!withApk) {
            emptyList()
        } else {
            listOf(
                ReleaseAsset("xingmu-tv-v$version-abc.apk", 12_000_000L, "https://example.com/a.apk"),
                ReleaseAsset("xingmu-tv-v$version-r8-abc.apk", 3_000_000L, "https://example.com/r.apk"),
            )
        },
    )

    private fun center(
        fetcher: ReleaseFetcher,
        prefs: UpdatePreferences = FakePrefs(),
        wifi: Boolean = true,
        freeBytes: Long = 2L * 1024 * 1024 * 1024,
        now: Long = 1_000_000L,
        version: String = "0.3.0",
    ): UpdateCenter = UpdateCenter(
        appContext = null,
        fetcher = fetcher,
        prefs = prefs,
        isWifi = { wifi },
        freeBytes = { freeBytes },
        now = { now },
        currentVersion = { version },
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
    )

    @Test
    fun `network failure is silent - no state change and no ui event`() = runTest {
        val center = center(FakeFetcher { throw IOException("offline") })

        val result = center.checkNow(manual = true, includePrerelease = false)

        assertTrue(result is UpdateCheckResult.Failed)
        assertEquals(UpdateState.Idle, center.state.value)
        assertTrue("失败时不得产生任何 UI 事件", center.events.replayCache.isEmpty())
    }

    @Test
    fun `auto check on non wifi is skipped without network`() = runTest {
        val fetcher = FakeFetcher { release("0.3.4") }
        val center = center(fetcher, wifi = false)

        val result = center.checkNow(manual = false, includePrerelease = false)

        assertTrue(result is UpdateCheckResult.Skipped)
        assertEquals(0, fetcher.calls)
        assertEquals(UpdateState.Idle, center.state.value)
        assertTrue(center.events.replayCache.isEmpty())
    }

    @Test
    fun `auto check within 24h is throttled`() = runTest {
        val fetcher = FakeFetcher { release("0.3.4") }
        val now = 10_000_000_000L
        val prefs = FakePrefs(stored = now - 60L * 60 * 1000)
        val center = center(fetcher, prefs = prefs, now = now)

        val result = center.checkNow(manual = false, includePrerelease = false)

        assertTrue(result is UpdateCheckResult.Skipped)
        assertEquals(0, fetcher.calls)
    }

    @Test
    fun `manual check ignores wifi and throttle`() = runTest {
        val fetcher = FakeFetcher { release("0.3.4") }
        val prefs = FakePrefs(stored = 1_000_000L)
        val center = center(fetcher, prefs = prefs, wifi = false)

        val result = center.checkNow(manual = true, includePrerelease = false)

        assertTrue(result is UpdateCheckResult.Available)
        assertEquals(1, fetcher.calls)
    }

    @Test
    fun `new version exposes available state and event`() = runTest {
        val center = center(FakeFetcher { release("0.3.4") })

        val result = center.checkNow(manual = true, includePrerelease = false)

        assertTrue(result is UpdateCheckResult.Available)
        val state = center.state.value as UpdateState.Available
        assertEquals("v0.3.4", state.candidate.release.tag)
        assertEquals(ApkFlavor.STANDARD, state.candidate.flavor)
        assertEquals(1, center.events.replayCache.size)
        assertTrue(center.events.replayCache.first() is UpdateUiEvent.NewVersion)
    }

    @Test
    fun `low storage selects r8 asset`() = runTest {
        val center = center(FakeFetcher { release("0.3.4") }, freeBytes = 100L * 1024 * 1024)

        center.checkNow(manual = true, includePrerelease = false)

        val state = center.state.value as UpdateState.Available
        assertEquals(ApkFlavor.R8, state.candidate.flavor)
        assertEquals("xingmu-tv-v0.3.4-r8-abc.apk", state.candidate.asset?.name)
    }

    @Test
    fun `release without apk falls back to page only`() = runTest {
        val center = center(FakeFetcher { release("0.3.4", withApk = false) })

        center.checkNow(manual = true, includePrerelease = false)

        val state = center.state.value as UpdateState.Available
        assertTrue(state.candidate.pageOnly)
        assertEquals(null, state.candidate.asset)
    }

    @Test
    fun `same version is up to date`() = runTest {
        val center = center(FakeFetcher { release("0.3.4") }, version = "0.3.4")

        val result = center.checkNow(manual = true, includePrerelease = false)

        assertTrue(result is UpdateCheckResult.UpToDate)
        assertTrue(center.state.value is UpdateState.UpToDate)
        assertTrue(center.events.replayCache.first() is UpdateUiEvent.UpToDate)
    }

    @Test
    fun `failure still records last check time to avoid retry storm`() = runTest {
        val prefs = FakePrefs()
        val center = center(FakeFetcher { throw IOException("boom") }, prefs = prefs)

        center.checkNow(manual = false, includePrerelease = false)

        assertEquals(1_000_000L, prefs.stored)
        assertEquals(UpdateState.Idle, center.state.value)
    }
}
