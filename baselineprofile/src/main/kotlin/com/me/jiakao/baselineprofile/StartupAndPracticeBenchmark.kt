package com.me.jiakao.baselineprofile

import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Baseline Profile 覆盖场景(TASK 交付物 5):
 * 冷启动 → 首页 → 进入答题页 → 滑动 30 题 → 打开答题卡 → 进入模拟考试。
 * 依赖 app debug 构建开启 testTagsAsResourceId(已在 JiakaoRoot 处理)。
 */
@RunWith(AndroidJUnit4::class)
class StartupAndPracticeBenchmark {

    @get:Rule
    val baselineProfileRule = BaselineProfileRule()

    private val packageName = "com.me.jiakao"

    @Test
    fun generate() = baselineProfileRule.collect(
        packageName = packageName,
        maxIterations = 5,
        includeInStartupProfile = true,
    ) {
        // 1) 冷启动
        pressHome()
        startActivityAndWait()
        device.waitForIdle()

        // 2) 首页 → 答题页(顺序练习)
        val seqEntry = device.findObject(By.text("顺序练习"))
        checkNotNull(seqEntry) { "未找到顺序练习入口" }
        seqEntry.click()
        device.waitForIdle()

        // 3) 左右滑动 30 题
        repeat(30) { i ->
            if (i % 2 == 0) device.swipe(900, 1200, 150, 1200, 12)
            else device.swipe(150, 1200, 900, 1200, 12)
            device.waitForIdle()
        }

        // 4) 打开答题卡并跳题
        device.findObject(By.text("答题卡"))?.click()
        device.waitForIdle()
        device.findObject(By.text("10"))?.click()
        device.waitForIdle()

        // 5) 退出到首页,进入模拟考试
        device.pressBack()
        device.waitForIdle()
        device.pressBack()
        device.waitForIdle()
        device.findObject(By.text("模拟考试"))?.click()
        device.waitForIdle()
    }
}

/** 滑动 30 题的帧耗时度量(FrameTimingMetric:P50/P90/P95/P99),与 Baseline Profile 生成解耦 */
@RunWith(AndroidJUnit4::class)
class PracticeSwipeBenchmark {

    @get:Rule
    val benchmarkRule = MacrobenchmarkRule()

    @Test
    fun swipe30Questions() = benchmarkRule.measureRepeated(
        packageName = "com.me.jiakao",
        metrics = listOf(FrameTimingMetric(), StartupTimingMetric()),
        compilationMode = CompilationMode.Full(),
        startupMode = StartupMode.COLD,
        iterations = 5,
    ) {
        pressHome()
        startActivityAndWait()
        device.findObject(By.text("顺序练习"))?.click()
        device.waitForIdle()
        repeat(30) { i ->
            if (i % 2 == 0) device.swipe(900, 1200, 150, 1200, 12)
            else device.swipe(150, 1200, 900, 1200, 12)
            device.waitForIdle()
        }
    }
}
