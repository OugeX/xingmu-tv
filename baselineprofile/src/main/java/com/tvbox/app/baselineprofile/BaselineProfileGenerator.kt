package com.tvbox.app.baselineprofile

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * t24：Baseline Profile 采集 —— 覆盖三条用户路径。
 *
 *   1) 首页 → 详情 → 播放
 *   2) 直播进入 → 切台
 *   3) 搜索 → 结果
 *
 * 生成（需要 API 28+ 设备/模拟器）：
 *   gradlew :app:generateBaselineProfile
 *
 * 当前团队唯一的真机是 LeTV X4-50（Android 6 / API 23），ART 从 P 才有 baseline profile，
 * 因此本模块只能编译、不能在本机采集 —— 状态 PENDING_DEVICE，等 API 28+ 设备到位再跑。
 *
 * 说明：Compose 没有稳定的 resource-id，这里用无障碍文本 + D-pad 键位驱动，
 * 首次在真机跑时可能需要按实际焦点顺序微调等待时间。
 */
@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {

    @get:Rule
    val rule = BaselineProfileRule()

    private val pkg = "com.tvbox.app"

    @Test
    fun baselineProfile() = rule.collect(packageName = pkg, includeInStartupProfile = true) {
        pressHome()
        startActivityAndWait()
        device.wait(Until.hasObject(By.pkg(pkg).depth(0)), 5_000)
        device.waitForIdle()

        // 首页首屏：等海报行渲染出来（焦点默认在第一张卡片）
        device.wait(Until.hasObject(By.pkg(pkg).hasDescendant(By.textContains("电影"))), 8_000)
        device.waitForIdle()
        device.pressDPadDown()
        device.waitForIdle()
        device.pressDPadUp()
        device.waitForIdle()

        // ---- 路径 1：首页 → 详情 → 播放 ----
        runCatching {
            device.pressDPadCenter()                 // 打开焦点卡片详情
            device.waitForIdle()
            waitText("播放", 8_000)
            device.pressDPadCenter()                 // 起播
            device.waitForIdle()
            Thread.sleep(1_200)
            device.pressBack()
            device.waitForIdle()
            device.pressBack()                       // 回到首页
            device.waitForIdle()
        }

        // ---- 路径 2：直播进入 → 切台 ----
        runCatching {
            clickTab("直播")
            device.waitForIdle()
            Thread.sleep(1_500)                      // 等首台起播
            repeat(3) {
                device.pressDPadRight()              // 切台（遥控右键）
                device.waitForIdle()
                Thread.sleep(800)
            }
            device.pressBack()
            device.waitForIdle()
        }

        // ---- 路径 3：搜索 → 结果 ----
        runCatching {
            clickTab("搜索")
            device.waitForIdle()
            typeText("老江湖")
            device.waitForIdle()
            device.pressDPadCenter()                 // 提交搜索
            device.wait(Until.hasObject(By.pkg(pkg)), 8_000)
            device.waitForIdle()
            device.pressBack()
            device.waitForIdle()
        }
    }

    private fun androidx.benchmark.macro.MacrobenchmarkScope.clickTab(label: String) {
        val tab = device.wait(Until.findObject(By.text(label)), 6_000)
        if (tab != null) {
            tab.click()
        } else {
            // 侧栏未展开时先按左键进侧栏再找
            device.pressDPadLeft()
            device.waitForIdle()
            device.wait(Until.findObject(By.text(label)), 3_000)?.click()
        }
        device.waitForIdle()
    }

    private fun androidx.benchmark.macro.MacrobenchmarkScope.typeText(text: String) {
        val field = device.wait(Until.findObject(By.focused(true)), 5_000)
        if (field != null) {
            runCatching { field.text = text }
        } else {
            // 搜索页首焦点不在输入框时，先居中键激活
            device.pressDPadCenter()
            device.waitForIdle()
            runCatching { device.wait(Until.findObject(By.focused(true)), 3_000)?.text = text }
        }
        device.waitForIdle()
    }

    private fun androidx.benchmark.macro.MacrobenchmarkScope.waitText(text: String, timeoutMs: Long) {
        device.wait(Until.hasObject(By.textContains(text)), timeoutMs)
    }
}
