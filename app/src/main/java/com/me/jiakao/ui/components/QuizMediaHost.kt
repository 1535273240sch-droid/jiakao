package com.me.jiakao.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.me.jiakao.core.model.MediaRef
import com.me.jiakao.core.media.QuizMedia

/**
 * 题目媒体宿主:限定尺寸后调用 :core:media 的 QuizMedia(合同签名),
 * 点击整块区域打开 MediaViewer。
 */
@Composable
fun QuizMediaHost(
    ref: MediaRef,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(210.dp)
            .clip(RoundedCornerShape(14.dp)),
    ) {
        QuizMedia(
            ref = ref,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Fit,
            onClick = onClick,
        )
    }
}
