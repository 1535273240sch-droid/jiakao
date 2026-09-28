package com.me.jiakao.core.media

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.ImageNotSupported
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import coil3.DrawableImage
import coil3.Image
import coil3.ImageLoader
import com.me.jiakao.core.media.internal.LocalAnimDecoderGate
import com.me.jiakao.core.media.internal.LocalAnimPlayOverride
import com.me.jiakao.core.media.internal.LocalAnimPlaybackFactory
import com.me.jiakao.core.media.internal.LocalQuizMediaImageHost
import com.me.jiakao.core.media.internal.LocalVideoPlayerPool
import com.me.jiakao.core.media.internal.MediaPlaceholder
import com.me.jiakao.core.media.internal.MediaTestTags
import com.me.jiakao.core.media.internal.isLifecycleResumed
import com.me.jiakao.core.media.internal.rememberMediaStore
import com.me.jiakao.core.media.internal.reportVisibility
import com.me.jiakao.core.media.internal.reserveMediaAspect
import com.me.jiakao.core.model.MediaKind
import com.me.jiakao.core.model.MediaRef
import com.me.jiakao.core.model.MediaStore
import kotlinx.coroutines.awaitCancellation

/**
 * 题目媒体控件（合同 §5 固定签名）。
 *
 * 三种 [MediaKind] 的行为：
 * - `IMAGE`：按控件尺寸降采样解码（交给 `AsyncImage` 的 `ConstraintsSizeResolver`），
 *   宽高比用 `MediaRef.width/height` 预占位，**首帧前后控件尺寸完全一致，杜绝布局跳动**；
 * - `ANIM`：仅在 `Lifecycle.RESUMED` **且**控件可见时占用解码预算（全局 ≤ 2），
 *   拿不到预算就退化为"仅首帧静态图"，不会产生任何后台动画解码线程；
 * - `VIDEO`：Media3 ExoPlayer，静音单曲循环，全局单播放器池，离开屏幕即 release。
 *
 * 文件缺失时显示 shimmer 骨架屏，并监听 [MediaStore.committed]：命中自身 sha256 后自动刷新
 * （回到前台时也会复查一次，覆盖"下载在后台完成"的情况）。
 *
 * 点击优先级：`onClick != null` 时整块区域交给调用方（典型用途是打开全屏查看器）；
 * `onClick == null` 且类型为 `ANIM` 时，点击切换播放/暂停（右下角有半透明状态图标）。
 * 若上层通过 `LocalAnimPlayOverride` 接管了播放意图（全屏查看器即如此），则不装点击监听。
 *
 * 无障碍：图片本身不设 `contentDescription`（题目正文已经描述了它），
 * 动图状态图标与"尚未下载"图标带有描述。
 *
 * @param ref 媒体引用（sha256 内容寻址）。
 * @param modifier 施加在控件根节点上；建议传入带约束的修饰符（如 `fillMaxWidth()`），
 *   这样宽高比预占位才能推算出另一轴的确切尺寸。
 * @param contentScale 缩放方式，默认 `Fit`。
 * @param onClick 可选点击回调。
 */
@Composable
fun QuizMedia(
    ref: MediaRef,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Fit,
    onClick: (() -> Unit)? = null,
) {
    val aspectRatio = remember(ref.width, ref.height) {
        if (ref.width > 0 && ref.height > 0) ref.width.toFloat() / ref.height.toFloat() else null
    }
    // fillMaxSize 先落实"父布局给多少就占多少"（该轴不受限时它是空操作），
    // reserveMediaAspect 再在单轴受限时按原始宽高比补出另一轴 —— 尺寸在图片解码完成前就已确定。
    // 子节点必须用 matchParentSize：Coil 的 AsyncImage 按**最小约束**定尺寸，
    // 只有拿到固定约束（matchParentSize 正好提供）才会真正铺满这个盒子。
    val sized = modifier.fillMaxSize().reserveMediaAspect(aspectRatio)

    when (ref.kind) {
        MediaKind.IMAGE -> StillMedia(ref, sized, contentScale, onClick)
        MediaKind.ANIM -> AnimatedMedia(ref, sized, contentScale, onClick)
        MediaKind.VIDEO -> VideoMedia(ref, sized, onClick)
    }
}

// ───────── IMAGE ─────────

@Composable
private fun StillMedia(
    ref: MediaRef,
    modifier: Modifier,
    contentScale: ContentScale,
    onClick: (() -> Unit)?,
) {
    val context = LocalContext.current
    val store = rememberMediaStore()
    val imageLoader = rememberImageLoader()
    val host = LocalQuizMediaImageHost.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val available = rememberMediaAvailable(store, ref)

    var onScreen by remember(ref.sha256) { mutableStateOf(false) }
    var image by remember(ref.sha256) { mutableStateOf<Image?>(null) }

    val request = remember(context, ref, store, lifecycle) {
        MediaRequests.image(context = context, ref = ref, store = store, lifecycle = lifecycle)
    }

    Box(modifier.clickableIf(onClick).reportVisibility { onScreen = it }) {
        if (image == null) {
            MediaPlaceholder(
                modifier = Modifier.matchParentSize().testTag(MediaTestTags.SKELETON),
                animate = onScreen,
            )
        }
        if (!available) {
            if (onScreen) {
                MissingBadge(Modifier.align(Alignment.Center).testTag(MediaTestTags.MISSING))
            }
        } else {
            host.Render(
                request = request,
                imageLoader = imageLoader,
                contentScale = contentScale,
                modifier = Modifier.matchParentSize().testTag(MediaTestTags.IMAGE),
                // 出错（含竞态下的文件消失）时保持骨架屏：等 committed 或下次回到前台再试。
                onSuccess = { image = it },
                onError = { },
            )
        }
    }
}

// ───────── ANIM ─────────

@Composable
private fun AnimatedMedia(
    ref: MediaRef,
    modifier: Modifier,
    contentScale: ContentScale,
    onClick: (() -> Unit)?,
) {
    val context = LocalContext.current
    val store = rememberMediaStore()
    val imageLoader = rememberImageLoader()
    val host = LocalQuizMediaImageHost.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val gate = LocalAnimDecoderGate.current
    val playbackFactory = LocalAnimPlaybackFactory.current
    val playOverride = LocalAnimPlayOverride.current
    val available = rememberMediaAvailable(store, ref)
    val resumed = isLifecycleResumed()

    var onScreen by remember(ref.sha256) { mutableStateOf(false) }
    var image by remember(ref.sha256) { mutableStateOf<Image?>(null) }
    var localWantsPlay by remember(ref.sha256) { mutableStateOf(true) }
    var hasSlot by remember(ref.sha256) { mutableStateOf(false) }

    // 上层接管时（全屏查看器）用它的意图，并且不再自己处理点击。
    val externallyControlled = playOverride != null
    val userWantsPlay = playOverride ?: localWantsPlay

    // 预算槽位：可见 + 前台才申请。暂停不释放槽位 —— 否则暂停会退化成"跳回首帧"，
    // 而用户暂停恰恰是为了盯住某一帧。
    val slotEligible = available && onScreen && resumed
    LaunchedEffect(slotEligible, gate) {
        if (!slotEligible) return@LaunchedEffect
        gate.acquire() // 注意：acquire 在 try 之外，被取消时不会误 release
        hasSlot = true
        try {
            awaitCancellation()
        } finally {
            hasSlot = false
            gate.release()
        }
    }

    val animatedDecode = hasSlot && available
    val animating = animatedDecode && userWantsPlay

    val request = remember(context, ref, store, lifecycle, animatedDecode) {
        MediaRequests.image(
            context = context,
            ref = ref,
            store = store,
            firstFrameOnly = !animatedDecode,
            lifecycle = lifecycle,
        )
    }

    val drawable = remember(image) { (image as? DrawableImage)?.drawable }
    LaunchedEffect(drawable, animating) {
        val playback = playbackFactory.create(drawable)
        if (animating) playback.start() else playback.stop()
    }

    val togglePlay: () -> Unit = { localWantsPlay = !localWantsPlay }

    Box(
        modifier = modifier
            .then(
                if (externallyControlled) {
                    Modifier
                } else {
                    Modifier.clickable { if (onClick != null) onClick() else togglePlay() }
                },
            )
            .reportVisibility { onScreen = it },
    ) {
        if (image == null) {
            MediaPlaceholder(
                modifier = Modifier.matchParentSize().testTag(MediaTestTags.SKELETON),
                animate = onScreen,
            )
        }
        if (!available) {
            if (onScreen) {
                MissingBadge(Modifier.align(Alignment.Center).testTag(MediaTestTags.MISSING))
            }
        } else {
            host.Render(
                request = request,
                imageLoader = imageLoader,
                contentScale = contentScale,
                modifier = Modifier.matchParentSize().testTag(MediaTestTags.IMAGE),
                onSuccess = { image = it },
                onError = { },
            )
        }
        AnimBadge(
            playing = animating,
            onToggle = togglePlay,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(6.dp)
                .testTag(MediaTestTags.ANIM_BADGE),
        )
    }
}

// ───────── VIDEO ─────────

@Composable
private fun VideoMedia(
    ref: MediaRef,
    modifier: Modifier,
    onClick: (() -> Unit)?,
) {
    val context = LocalContext.current
    val store = rememberMediaStore()
    val pool = LocalVideoPlayerPool.current
    val available = rememberMediaAvailable(store, ref)
    val resumed = isLifecycleResumed()

    var onScreen by remember(ref.sha256) { mutableStateOf(false) }
    var player by remember(ref.sha256) { mutableStateOf<ExoPlayer?>(null) }
    val file = remember(ref.sha256, available) { store.file(ref) }
    // 同一份组合里唯一的持有者标识：只有它能把播放器还给池子。
    val owner = remember { Any() }

    Box(modifier.clickableIf(onClick).reportVisibility { onScreen = it }) {
        if (!available) {
            MediaPlaceholder(
                modifier = Modifier.matchParentSize().testTag(MediaTestTags.SKELETON),
                animate = onScreen,
            )
            if (onScreen) {
                MissingBadge(Modifier.align(Alignment.Center).testTag(MediaTestTags.MISSING))
            }
            return@Box
        }

        if (file == null) {
            MediaPlaceholder(modifier = Modifier.matchParentSize().testTag(MediaTestTags.SKELETON))
        } else {
            val shouldPlay = onScreen && resumed
            DisposableEffect(file.absolutePath, shouldPlay) {
                if (shouldPlay) {
                    player = pool.acquire(context = context, owner = owner, file = file)
                } else {
                    pool.pause(owner)
                }
                onDispose {
                    player = null
                    pool.release(owner) // 离开屏幕即释放（任务书 §3）
                }
            }
            val activePlayer = player
            if (activePlayer == null) {
                MediaPlaceholder(modifier = Modifier.matchParentSize().testTag(MediaTestTags.SKELETON))
            } else {
                AndroidView(
                    modifier = Modifier.matchParentSize().testTag(MediaTestTags.VIDEO),
                    factory = { ctx ->
                        PlayerView(ctx).apply {
                            useController = false
                            this.player = activePlayer
                        }
                    },
                    update = { it.player = activePlayer },
                )
            }
        }
    }
}

// ───────── 共享小件 ─────────

@Composable
private fun rememberImageLoader(): ImageLoader {
    val context = LocalContext.current
    return remember(context) { MediaImageLoader.of(context) }
}

/**
 * 文件是否已就绪：[MediaStore.file] 的存在性判定 + `committed` 事件 + 回到前台时的复查。
 */
@Composable
private fun rememberMediaAvailable(store: MediaStore, ref: MediaRef): Boolean {
    var available by remember(store, ref.sha256, ref.ext) { mutableStateOf(store.file(ref) != null) }

    LaunchedEffect(store, ref.sha256, ref.ext) {
        // 先复查一次再订阅，堵住"判定 → 订阅"之间漏掉的 commit。
        available = checkExists(store, ref)
        store.committed.collect { sha ->
            if (sha.equals(ref.sha256, ignoreCase = true)) {
                available = checkExists(store, ref)
            }
        }
    }

    val resumed = isLifecycleResumed()
    LaunchedEffect(resumed) {
        if (resumed) available = checkExists(store, ref)
    }
    return available
}

/** 丢弃存在性缓存后重新查盘（进程外写入的文件也能被发现）。 */
private fun checkExists(store: MediaStore, ref: MediaRef): Boolean =
    (store as? MediaStoreImpl)?.refresh(ref) ?: (store.file(ref) != null)

@Composable
private fun MissingBadge(modifier: Modifier = Modifier) {
    Icon(
        imageVector = Icons.Outlined.ImageNotSupported,
        contentDescription = "图片尚未下载",
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.size(32.dp).alpha(0.45f),
    )
}

@Composable
private fun AnimBadge(playing: Boolean, onToggle: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(30.dp)
            .alpha(0.55f)
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.35f))
            .clickable(onClick = onToggle),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
            contentDescription = if (playing) "暂停动图" else "播放动图",
            tint = Color.White,
            modifier = Modifier.size(18.dp),
        )
    }
}

private fun Modifier.clickableIf(onClick: (() -> Unit)?): Modifier =
    if (onClick == null) this else clickable(onClick = onClick)
