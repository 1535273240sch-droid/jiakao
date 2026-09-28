package com.me.jiakao.core.media

import com.me.jiakao.core.model.MediaKind
import com.me.jiakao.core.model.MediaRef
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * [MediaStoreImpl] 的单元测试（纯 JVM，不需要设备/Robolectric —— 实现刻意不依赖 Context）。
 *
 * 覆盖验收清单里的两条硬指标：**commit 篡改 1 字节 → 返回 false 且不落盘**、
 * 以及并发 commit 的串行化与幂等。
 */
class MediaStoreImplTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var root: File

    private fun newStore(): MediaStoreImpl {
        root = File(temp.root, "media")
        return MediaStoreImpl(root, Dispatchers.IO)
    }

    // ───────── commit ─────────

    @Test
    fun `commit 成功后文件落在内容寻址路径上并维护 usedBytes 与 committed 事件`() = runTest {
        val store = newStore()
        val bytes = "hello-media".toByteArray()
        val ref = refOf(bytes)
        val tmp = writeTmp("a.part", bytes)

        val emitted = async { store.committed.first() }
        runCurrent() // 先让收集者订阅上，避免漏掉事件（SharedFlow replay = 0）
        assertTrue(store.commit(ref, tmp))

        assertEquals(sha(ref) + ".webp", store.file(ref)?.name)
        assertEquals("${sha(ref).substring(0, 2)}", store.file(ref)?.parentFile?.name)
        assertEquals(bytes.toList(), store.file(ref)!!.readBytes().toList())
        assertEquals(bytes.size.toLong(), store.usedBytes())
        assertEquals(sha(ref), emitted.await())
        assertFalse("临时文件应已被移动", tmp.exists())
    }

    @Test
    fun `commit 篡改 1 字节 → 返回 false 且不落盘`() = runTest {
        val store = newStore()
        val bytes = ByteArray(1024) { (it % 251).toByte() }
        val ref = refOf(bytes)
        val tampered = bytes.copyOf().also { it[512] = (it[512] + 1).toByte() }
        val tmp = writeTmp("tampered.part", tampered)

        assertFalse(store.commit(ref, tmp))
        assertNull(store.file(ref))
        assertFalse("校验失败必须删除临时文件", tmp.exists())
        assertEquals(0L, store.usedBytes())
        assertFalse(File(root, sha(ref).substring(0, 2)).exists())
    }

    @Test
    fun `commit 字节数不符 → 返回 false 且不落盘`() = runTest {
        val store = newStore()
        val bytes = "abc".toByteArray()
        val ref = refOf(bytes).copy(bytes = bytes.size + 1L)
        val tmp = writeTmp("size.part", bytes)

        assertFalse(store.commit(ref, tmp))
        assertNull(store.file(ref))
        assertFalse(tmp.exists())
    }

    @Test
    fun `同一 sha 的并发 commit 被串行化且第二个直接视为成功`() = runTest {
        val store = newStore()
        val bytes = ByteArray(64 * 1024 + 7) { (it % 97).toByte() }
        val ref = refOf(bytes)
        val first = writeTmp("first.part", bytes)
        val second = writeTmp("second.part", bytes)

        val results = listOf(first, second)
            .map { file -> async(Dispatchers.IO) { store.commit(ref, file) } }
            .awaitAll()

        assertEquals(listOf(true, true), results)
        assertEquals(bytes.toList(), store.file(ref)!!.readBytes().toList())
        assertEquals("两个 tmp 都应被清掉", 0, root.walkTopDown().filter { it.extension == "part" }.count())
        assertEquals(bytes.size.toLong(), store.usedBytes())
    }

    @Test
    fun `重复 commit 同一内容不会改变 usedBytes`() = runTest {
        val store = newStore()
        val bytes = "same".toByteArray()
        val ref = refOf(bytes)
        assertTrue(store.commit(ref, writeTmp("1.part", bytes)))
        assertEquals(bytes.size.toLong(), store.usedBytes())

        assertTrue(store.commit(ref, writeTmp("2.part", bytes)))
        assertEquals("第二个 commit 命中已存在文件，不应重复计数", bytes.size.toLong(), store.usedBytes())
    }

    @Test
    fun `commit 发射小写 sha256`() = runTest {
        val store = newStore()
        val bytes = "upper".toByteArray()
        val ref = refOf(bytes).copy(sha256 = refOf(bytes).sha256.uppercase())
        val emitted = async { store.committed.first() }
        runCurrent() // 先让收集者订阅上，避免漏掉事件（SharedFlow replay = 0）
        assertTrue(store.commit(ref, writeTmp("u.part", bytes)))
        assertEquals(refOf(bytes).sha256, emitted.await())
    }

    // ───────── file / 路径安全 ─────────

    @Test
    fun `非法 sha 或 ext 一律拒绝`() = runTest {
        val store = newStore()
        val bytes = "x".toByteArray()

        assertNull(store.file(refOf(bytes).copy(sha256 = "not-a-sha")))
        assertNull(store.file(refOf(bytes).copy(ext = "../../etc/passwd")))
        assertNull(store.file(refOf(bytes).copy(sha256 = "../" + "a".repeat(64))))

        val tmp = writeTmp("evil.part", bytes)
        assertFalse(store.commit(refOf(bytes).copy(ext = "../evil"), tmp))
        assertFalse("非法 ref 也必须清掉临时文件", tmp.exists())
    }

    @Test
    fun `file 命中存在性缓存，refresh 强制复查`() = runTest {
        val store = newStore()
        val ref = refOf("cached".toByteArray())

        assertNull(store.file(ref))
        // 进程外写入：模拟 04 的下载器 / adb push / 离线包解压
        val target = MediaRefFetcher.pathOf(root, ref)!!
        target.parentFile!!.mkdirs()
        target.writeBytes("cached".toByteArray())

        assertNull("存在性缓存的负面结果会保留到下次失效", store.file(ref))
        assertTrue(store.refresh(ref))
        assertNotNull(store.file(ref))
    }

    @Test
    fun `usedBytes 增量维护而不是每次全盘扫描`() = runTest {
        val store = newStore()
        val bytes = "counted".toByteArray()
        val ref = refOf(bytes)
        assertTrue(store.commit(ref, writeTmp("c.part", bytes)))

        assertEquals(bytes.size.toLong(), store.usedBytes())

        // 直接往盘上丢一个文件：索引已初始化，因此计数不应变化（证明不是全盘扫描）。
        val stray = File(File(root, "ff"), "f".repeat(64) + ".webp")
        stray.parentFile!!.mkdirs()
        stray.writeBytes(ByteArray(4096))
        assertEquals(bytes.size.toLong(), store.usedBytes())

        // retain 会把它算进去并删除。
        assertEquals(1, kotlinx.coroutines.runBlocking { store.retain(setOf(sha(ref))) })
        assertEquals(bytes.size.toLong(), store.usedBytes())
    }

    // ───────── retain ─────────

    @Test
    fun `retain 删除未被引用的媒体并回收计数与空目录`() = runTest {
        val store = newStore()
        val keepBytes = "keep".toByteArray()
        val dropBytes1 = "drop-1".toByteArray()
        val dropBytes2 = "drop-2".toByteArray()
        val keep = refOf(keepBytes)
        val drop1 = refOf(dropBytes1)
        val drop2 = refOf(dropBytes2)

        assertTrue(store.commit(keep, writeTmp("k.part", keepBytes)))
        assertTrue(store.commit(drop1, writeTmp("d1.part", dropBytes1)))
        assertTrue(store.commit(drop2, writeTmp("d2.part", dropBytes2)))
        assertEquals((keepBytes.size + dropBytes1.size + dropBytes2.size).toLong(), store.usedBytes())

        val removed = store.retain(setOf(sha(keep)))

        assertEquals(2, removed)
        assertNotNull(store.file(keep))
        assertNull(store.file(drop1))
        assertNull(store.file(drop2))
        assertEquals(keepBytes.size.toLong(), store.usedBytes())
        assertFalse("空桶目录应被清理", File(root, sha(drop1).substring(0, 2)).exists())
    }

    @Test
    fun `retain 空集合等于清空`() = runTest {
        val store = newStore()
        val bytes = "all".toByteArray()
        assertTrue(store.commit(refOf(bytes), writeTmp("a.part", bytes)))

        assertEquals(1, store.retain(emptySet()))
        assertEquals(0L, store.usedBytes())
    }

    // ───────── helpers ─────────

    private fun sha(ref: MediaRef): String = ref.sha256.lowercase()

    private fun refOf(bytes: ByteArray, ext: String = "webp", kind: MediaKind = MediaKind.IMAGE): MediaRef =
        MediaRef(
            sha256 = MessageDigest.getInstance("SHA-256").digest(bytes).toHex(),
            ext = ext,
            kind = kind,
            width = 480,
            height = 360,
            bytes = bytes.size.toLong(),
        )

    private fun writeTmp(name: String, bytes: ByteArray): File {
        val dir = File(temp.root, "dl").apply { mkdirs() }
        return File(dir, name).apply { writeBytes(bytes) }
    }
}
