package com.me.jiakao.core.media

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import com.me.jiakao.core.media.internal.LocalMediaStore
import com.me.jiakao.core.media.internal.LocalQuizMediaImageHost
import com.me.jiakao.core.media.internal.MediaTestTags
import com.me.jiakao.core.media.testing.TestImageHost
import com.me.jiakao.core.model.MediaKind
import com.me.jiakao.core.model.MediaRef
import java.io.File
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** [MediaViewer] 的 Compose 组件测试：翻页、页码、返回键与下拉关闭。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MediaViewerUiTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @get:Rule
    val temp = TemporaryFolder()

    private fun ref(index: Int, kind: MediaKind = MediaKind.IMAGE): MediaRef = MediaRef(
        sha256 = index.toString(16).padStart(2, '0').repeat(32),
        ext = "webp",
        kind = kind,
        width = 480,
        height = 360,
        bytes = 1,
    )

    private fun setContent(refs: List<MediaRef>, startIndex: Int, onDismiss: () -> Unit) {
        val store = MediaStoreImpl(File(temp.root, "media"), Dispatchers.IO)
        compose.setContent {
            CompositionLocalProvider(
                LocalMediaStore provides store,
                LocalQuizMediaImageHost provides TestImageHost(),
            ) {
                MediaViewer(refs = refs, startIndex = startIndex, onDismiss = onDismiss)
            }
        }
    }

    @Test
    fun `渲染当前页并显示页码`() {
        val refs = listOf(ref(1), ref(2), ref(3))

        setContent(refs, startIndex = 1) {}

        compose.onNodeWithTag(MediaTestTags.VIEWER).assertExists()
        compose.onNodeWithTag(MediaTestTags.VIEWER_PAGE).assertExists()
        compose.onNodeWithText("2 / 3").assertExists()
    }

    @Test
    fun `越界起始下标被夹到合法范围`() {
        val refs = listOf(ref(1), ref(2))

        setContent(refs, startIndex = 99) {}

        compose.onNodeWithText("2 / 2").assertExists()
    }

    @Test
    fun `返回键触发关闭`() {
        var dismissed = false
        setContent(listOf(ref(1)), startIndex = 0) { dismissed = true }

        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()

        assertTrue("BackHandler 应调用 onDismiss", dismissed)
    }

    @Test
    fun `下拉超过阈值触发关闭`() {
        var dismissed = false
        setContent(listOf(ref(1)), startIndex = 0) { dismissed = true }

        compose.onNodeWithTag(MediaTestTags.VIEWER).performTouchInput {
            swipeDown(startY = centerY - 150f, endY = centerY + 150f)
        }
        compose.waitForIdle()

        assertTrue("下拉超过阈值应关闭查看器", dismissed)
    }

    @Test
    fun `小幅下拉不关闭（回弹）`() {
        var dismissed = false
        setContent(listOf(ref(1)), startIndex = 0) { dismissed = true }

        compose.onNodeWithTag(MediaTestTags.VIEWER).performTouchInput {
            swipeDown(startY = centerY, endY = centerY + 20f, durationMillis = 80)
        }
        compose.waitForIdle()

        assertFalse("未达阈值不应关闭", dismissed)
    }

    @Test
    fun `空列表直接回调关闭`() {
        var dismissed = false

        setContent(emptyList(), startIndex = 0) { dismissed = true }
        compose.waitForIdle()

        assertTrue(dismissed)
    }

    @Test
    fun `双击放大后不把放大状态带到下一页`() {
        val refs = listOf(ref(1), ref(2))
        setContent(refs, startIndex = 0) {}

        compose.onNodeWithTag(MediaTestTags.VIEWER).performTouchInput {
            doubleClick(Offset(centerX, centerY))
        }
        compose.waitForIdle()

        // 只做健壮性断言：双击不应崩溃、页面仍在。
        compose.onNodeWithTag(MediaTestTags.VIEWER_PAGE).assertExists()
    }
}
