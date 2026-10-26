package com.watchface.idtool.pet

import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.SurfaceTexture
import android.media.MediaPlayer
import android.view.Surface
import android.view.TextureView
import android.view.View
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.draw.clipToBounds
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
 * 桌宠渲染层。
 *
 * 【透明实现】
 * 动画 MP4 是「纯黑底 + 角色白色描边」。给 TextureView 设置
 * `PorterDuff.Mode.SCREEN`（滤色）xfermode：
 *   SCREEN 公式 result = 1-(1-src)(1-dst)
 *   · src=黑(0) → result = dst（等于背景，即完全透明）
 *   · src=白(1) → result = 1（纯白保留）
 * 与网页版 dsh-pet 的 CSS 混合模式一致。
 *
 * 【尺寸修正】
 * 视频 640×360 里角色只占中间 240×285 区域（HIT_BOX 200,50 ~ 440,335）。
 * 直接铺满 View 会让角色只占 37.5% 宽而显得很小。
 * 这里把视频放大 640/240=2.667 倍并偏移，让**角色区域刚好填满 View**——
 * 于是 size=180dp 时角色实际就是 180dp 宽，视觉上正常大小。
 *
 * 交互：单击 → 点击回应；长按 → 菜单；拖拽 → 弹簧跟手 + 甩抛。
 */

/** 视频里角色的包围盒（640×360 基准，对齐 dsh-pet HIT_BOX） */
private const val ROLE_X0 = 200f
private const val ROLE_Y0 = 50f
private const val ROLE_X1 = 440f
private const val ROLE_Y1 = 335f
private const val VIDEO_W = 640f
private const val VIDEO_H = 360f

/** 角色区域宽高比（宽 / 高） */
private const val ROLE_ASPECT = (ROLE_X1 - ROLE_X0) / (ROLE_Y1 - ROLE_Y0)  // 0.842

@Composable
fun PetSprite(
    state: PetState,
    modifier: Modifier = Modifier,
    onLongPress: () -> Unit = {},
) {
    BoxWithConstraints(modifier.fillMaxSize()) {
        val density = LocalDensity.current
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

        // View 尺寸：宽 = size，高按角色比例（不是视频的 9:16）
        val viewW = state.size.dp
        val viewH = (state.size / ROLE_ASPECT).dp

        // 视频在 View 里的放大与偏移（让角色区域填满 View）
        val scale = VIDEO_W / (ROLE_X1 - ROLE_X0)          // 2.667
        val videoW = (state.size * scale).dp
        val videoH = (state.size * scale * VIDEO_H / VIDEO_W).dp  // 1.5 * size
        val offX = (-(ROLE_X0 / (ROLE_X1 - ROLE_X0)) * state.size).dp  // -0.833 * size
        val offY = (-(ROLE_Y0 / (ROLE_Y1 - ROLE_Y0)) * state.size).dp  // -0.175 * size

        Box(
            modifier = Modifier
                .offset {
                    IntOffset(state.x.roundToInt(), state.y.roundToInt())
                }
                .size(width = viewW, height = viewH)
                .clipToBounds()
                .graphicsLayer {
                    // 朝向翻转 + Q 弹挤压（作用于整个角色框）
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
                            state.updateDragTarget(state.x + dragAmount.x, state.y + dragAmount.y)
                            trail.add(PetPhysics.DragSample(now, state.x, state.y))
                            if (trail.size > 30) trail.removeAt(0)
                        },
                        onDragEnd = { state.releaseDrag(trail.toList(), System.currentTimeMillis()) },
                        onDragCancel = { state.releaseDrag(emptyList(), System.currentTimeMillis()) }
                    )
                }
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = { state.onClickReaction() },
                        onLongPress = { onLongPress() }
                    )
                }
        ) {
            // 视频层：放大 + 偏移，让角色区域对齐 View 左上角；黑底由 SCREEN 混合滤掉
            PetVideo(
                animFile = state.currentAnim.key,
                modifier = Modifier
                    .size(width = videoW, height = videoH)
                    .offset(x = offX, y = offY)
            )
        }

        // 对话气泡
        state.bubbleText?.let { text ->
            val bubbleX = with(density) { (state.x + state.size * 0.7f).toDp() }
            val bubbleY = with(density) { (state.y - 80).coerceAtLeast(0f).toDp() }
            Box(
                modifier = Modifier
                    .offset {
                        IntOffset(
                            with(density) { bubbleX.roundToPx() },
                            with(density) { bubbleY.roundToPx() }
                        )
                    }
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

/**
 * 宠物视频播放器 —— TextureView + MediaPlayer，循环播放单个动画。
 * 关键：setLayerType(HARDWARE) + SCREEN xfermode 让黑色背景透明。
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
                // 黑色背景滤色为透明：SCREEN 混合下 黑(0) → 显示背景
                setLayerType(View.LAYER_TYPE_HARDWARE, Paint().apply {
                    xfermode = PorterDuffXfermode(PorterDuff.Mode.SCREEN)
                    isAntiAlias = false
                    isFilterBitmap = true
                })
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
            // 动画缺失或解码失败：静默跳过
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
