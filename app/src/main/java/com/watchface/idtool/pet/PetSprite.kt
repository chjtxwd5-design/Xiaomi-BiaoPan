package com.watchface.idtool.pet

/** 角色区域宽高比（宽/高）：视频 640x360 里角色占 240x285 */
const val PET_ROLE_ASPECT = 240f / 285f

import android.view.View
import androidx.compose.foundation.Image
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
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlin.math.roundToInt

/**
 * 桌宠渲染层 —— 双模式：
 *
 * 1. 安全模式（PetStorage.videoEnabled=false，默认）：
 *    Compose 静态形象 + 呼吸动画 + Q 弹挤压 + 拖拽物理。零 GL，绝对不崩。
 * 2. 视频模式（设置开启「动态视频渲染」）：
 *    PetChromaView 软件色键（TextureView getBitmap 逐帧处理），黑底真透明。
 */
@Composable
fun PetSprite(
    state: PetState,
    modifier: Modifier = Modifier,
    onLongPress: () -> Unit = {},
) {
    BoxWithConstraints(modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val context = LocalContext.current
        val trail = remember { mutableListOf<PetPhysics.DragSample>() }

        // 每帧驱动状态机
        LaunchedEffect(Unit) {
            var lastNanos = 0L
            while (true) {
                withFrameNanos { now: Long ->
                    if (lastNanos != 0L) {
                        val dtMs = ((now - lastNanos) / 1_000_000L).coerceIn(1L, 50L)
                        state.tick(dtMs)
                    }
                    lastNanos = now
                }
            }
        }

        val viewW = state.size.dp
        val viewH = (state.size / PET_ROLE_ASPECT).dp

        // 呼吸动画（安全模式的动感）
        val breath = androidx.compose.animation.core.rememberInfiniteTransition(label = "petBreath")
        val breathScale by breath.animateFloat(
            initialValue = 1f,
            targetValue = 1.04f,
            animationSpec = androidx.compose.animation.core.infiniteRepeatable(
                androidx.compose.animation.core.tween(1400),
                androidx.compose.animation.core.RepeatMode.Reverse
            ),
            label = "breathScale"
        )

        Box(
            modifier = Modifier
                .offset { IntOffset(state.x.roundToInt(), state.y.roundToInt()) }
                .size(width = viewW, height = viewH)
                .graphicsLayer {
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
            if (PetStorage.videoEnabled) {
                // ---- 视频模式：软件色键播放器 ----
                AndroidView(
                    factory = { ctx: android.content.Context ->
                        try {
                            PetChromaView(ctx)
                        } catch (e: Exception) {
                            android.util.Log.e("PetSprite", "chroma create failed: ${e.message}")
                            View(ctx)
                        }
                    },
                    update = { view: View -> },
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                // ---- 安全模式：静态形象 + 呼吸 ----
                Image(
                    bitmap = imageResource(id = com.watchface.idtool.R.drawable.wallpaper_tomoyo),
                    contentDescription = null,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            scaleX = breathScale
                            scaleY = breathScale
                        }
                )
            }
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
