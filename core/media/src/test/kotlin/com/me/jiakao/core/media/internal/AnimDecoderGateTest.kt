package com.me.jiakao.core.media.internal

import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** [AnimDecoderGate] 的单元测试：预算上限、排队唤醒、以及"未持有时 release 不放大预算"。 */
class AnimDecoderGateTest {

    @Test
    fun `最多同时两个解码器，第三个会挂起等待`() = runTest {
        val gate = AnimDecoderGate(maxActive = 2)

        assertTrue(gate.tryAcquire())
        assertTrue(gate.tryAcquire())
        assertFalse("第三个不应拿到槽位", gate.tryAcquire())
        assertEquals(2, gate.activeCount.value)

        var thirdAcquired = false
        val third = launch {
            gate.acquire()
            thirdAcquired = true
        }
        runCurrent()
        assertFalse("槽位用尽时 acquire 应挂起而不是返回", thirdAcquired)

        gate.release()
        runCurrent()
        assertTrue("释放一个槽位后排队者应被唤醒", thirdAcquired)
        assertEquals(2, gate.activeCount.value)

        gate.release()
        gate.release()
        third.cancel()
        assertEquals(0, gate.activeCount.value)
    }

    @Test
    fun `未持有时 release 是空操作，不会凭空放大预算`() {
        val gate = AnimDecoderGate(maxActive = 2)

        gate.release()
        gate.release()
        assertEquals(0, gate.activeCount.value)

        // 预算仍是 2：第三次 tryAcquire 必须失败。
        assertTrue(gate.tryAcquire())
        assertTrue(gate.tryAcquire())
        assertFalse(gate.tryAcquire())
    }

    @Test
    fun `多次 acquire-release 后预算不漂移`() = runTest {
        val gate = AnimDecoderGate(maxActive = 1)

        repeat(50) {
            gate.acquire()
            assertEquals(1, gate.activeCount.value)
            gate.release()
            assertEquals(0, gate.activeCount.value)
        }
        assertTrue(gate.tryAcquire())
        assertFalse(gate.tryAcquire())
    }
}
