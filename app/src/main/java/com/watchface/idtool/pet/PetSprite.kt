package com.watchface.idtool.pet

import android.graphics.SurfaceTexture
import android.media.MediaPlayer
import android.view.Surface
import android.view.TextureView
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlin.math.roundToInt

/**
 * 桌宠渲染层 —— 把 PetState 渲染成可见的宠物。
 *
 * 透明实现：动画 MP4 是黑底 + 白色描边。
 * Android 端用 [androidx.compose.ui.graphics.BlendMode.Screen] 滤色混合实现透明：
 *   result = 1 - (1-src)×(1-dst)：纯黑(0) → 透明，纯白保持。
 *   与网页版 dsh-pet 的 CSS 混合模式效果一致。
 *   （注：本版本先用普通绘制保证可用性，滤色混合在 PetSprite 的父层做）
 *
 * 交互：
 *   · 单击 → 随机点击回应动画 + Q 弹
 *   · 长按 → 右键菜单
 *   · 拖拽 → 跟手弹簧 + 松手甩抛物理
 */
@Composable
fun PetSprite(
    state: PetState,
    modifier: Modifier = Modifier,
    onLongPress: () -> Unit = {},
) {
    BoxWithConstraints(modifier.fillMaxSize()) {
        val density = LocalDensity.current

        // 拖拽轨迹采样（估速用）
        val trail = remember { mutableListOf<PetPhysics.DragSample>() }

        // 每帧驱动状态机
        LaunchedEffect(Unit) {
            var lastNanos = 0L
            while (true) {
                withFrameNanos { now ->
                    if (lastNanos != 0L) {
                        val dtMs = ((now - lastNanos) / 1_000_000L).coerceIn(1L, 50L)
                        state.tick(dtMs)
                    }
                    lastNanos = now
                }
            }
        }

        val pw = with(density) { state.size.toDp() }
        val ph = with(density) { (state.size * 9f / 16f).toDp() }

        Box(
            modifier = Modifier
                .offset { IntOffset(state.x.roundToInt(), state.y.roundToInt()) }
                .size(width = pw, height = ph)
                .graphicsLayer {
                    // 朝向翻转 + Q 弹挤压
                    val flip = if (state.facingRight) 1f else -1f
                    scaleX = state.squashScaleX * flip
                    scaleY = state.squashScaleY
                }
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = { offset ->
                            trail.clear()
                            state.startDrag()
                            trail.add(
                                PetPhysics.DragSample(
                                    System.currentTimeMillis(),
                                    state.x + offset.x,
                                    state.y + offset.y
                                )
                            )
                        },
                        onDrag = { change, dragAmount ->
                            change.consume()
                            val now = System.currentTimeMillis()
                            state.updateDragTarget(
                                state.x + dragAmount.x,
                                state.y + dragAmount.y
                            )
                            trail.add(PetPhysics.DragSample(now, state.x, state.y))
                            if (trail.size > 30) trail.removeAt(0)
                        },
                        onDragEnd = {
                            state.releaseDrag(trail.toList(), System.currentTimeMillis())
                        },
                        onDragCancel = {
                            state.releaseDrag(emptyList(), System.currentTimeMillis())
                        }
                    )
                }
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = { state.onClickReaction() },
                        onLongPress = { onLongPress() }
                    )
                }
        ) {
            PetVideo(animFile = state.currentAnim.file, modifier = Modifier.fillMaxSize())
        }

        // 对话气泡
        state.bubbleText?.let { text ->
            val bubbleX = with(density) { (state.x + state.size * 0.55f).toDp() }
            val bubbleY = with(density) { (state.y - 70).toInt().coerceAtLeast(0).toDp() }
            Box(
                modifier = Modifier
                    .offset { IntOffset(bubbleX.roundToDp(density), bubbleY.roundToDp(density)) }
                    .width(180.dp)
                    .wrapContentHeight()
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color.White.copy(alpha = 0.94f))
                    .padding(10.dp)
            ) {
                Text(
                    text = text,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = Color(0xFF1F2A44)
                )
            }
        }
    }
}

private fun androidx.compose.ui.unit.Dp.roundToDp(density: androidx.compose.ui.unit.Density): Int =
    this.value.toInt()

/**
 * 宠物视频播放器 —— TextureView + MediaPlayer，循环播放单个动画。
 * 动画切换时重建 MediaPlayer。
 */
@Composable
private fun PetVideo(
    animFile: String,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var textureView by remember { mutableStateOf<TextureView?>(null) }
    var mediaPlayer by remember { mutableStateOf<MediaPlayer?>(null) }

    AndroidView(
        factory = { ctx ->
            TextureView(ctx).apply {
                surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                    override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
                        textureView = this@apply
                    }

                    override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) = Unit
                    override fun onSurfaceTextureDestroyed(st: SurfaceTexture) = true
                    override fun onSurfaceTextureUpdated(st: SurfaceTexture) = Unit
                }
            }
        },
        modifier = modifier
    )

    // 动画切换 → 重建播放器
    DisposableEffect(animFile, textureView) {
        val tv = textureView
        if (tv == null) return@DisposableEffect onDispose { }
        val st = tv.surfaceTexture
        if (st == null) return@DisposableEffect onDispose { }

        val mp = MediaPlayer()
        try {
            val afd = context.assets.openFd("pet/$animFile.mp4")
            mp.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
            afd.close()
            mp.setSurface(Surface(st))
            mp.isLooping = true
            mp.setVolume(0f, 0f)
            mp.setAudioAttributes(
                android.media.AudioAttributes.Builder()
                    .setUsage(android.media.AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            mp.prepare()
            mp.start()
            mediaPlayer = mp
        } catch (_: Exception) {
            // 动画缺失或解码失败：静默跳过（宠物仍可拖动/点击）
        }

        onDispose {
            mediaPlayer?.run {
                try {
                    stop()
                    release()
                } catch (_: Exception) { }
            }
            mediaPlayer = null
        }
    }
}
