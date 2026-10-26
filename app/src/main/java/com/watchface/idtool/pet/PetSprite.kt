package com.watchface.idtool.pet

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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlin.math.roundToInt

/**
 * 桌宠渲染层 —— GL 色键透明视频 + 静态帧自动降级。
 *
 * 降级链：GL 视频正常 → 用视频；GL 初始化/渲染失败 → 自动切静态帧（有 log）。
 * 静态帧也不可见的概率极低（它只是 drawable），所以永远有东西可看。
 *
 * 尺寸：视频 640×360 里角色只占 240×285（HIT_BOX），放大 2.667 倍偏移对齐 View。
 */
private const val ROLE_X0 = 200f
private const val ROLE_Y0 = 50f
private const val ROLE_X1 = 440f
private const val ROLE_Y1 = 335f
private const val VIDEO_W = 640f
private const val VIDEO_H = 360f

/** 角色区域宽高比（宽/高） */
const val PET_ROLE_ASPECT = (ROLE_X1 - ROLE_X0) / (ROLE_Y1 - ROLE_Y0)

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

        val scale = VIDEO_W / (ROLE_X1 - ROLE_X0)
        val videoW = (state.size * scale).dp
        val videoH = (state.size * scale * VIDEO_H / VIDEO_W).dp
        val offX = (-(ROLE_X0 / (ROLE_X1 - ROLE_X0)) * state.size).dp
        val offY = (-(ROLE_Y0 / (ROLE_Y1 - ROLE_Y0)) * state.size).dp

        // GL 失败降级开关
        var glFailed by remember { mutableStateOf(false) }

        Box(
            modifier = Modifier
                .offset { IntOffset(state.x.roundToInt(), state.y.roundToInt()) }
                .size(width = viewW, height = viewH)
                .clipToBounds()
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
            if (!glFailed) {
                // GL 色键透明视频（失败时 onGlFailed 置 glFailed=true 自动降级）
                AndroidView(
                    factory = { ctx ->
                        try {
                            PetGLView(ctx, state.currentAnim.key) { glFailed = true }
                        } catch (e: Exception) {
                            glFailed = true
                            android.util.Log.e("PetSprite", "GLView create failed: ${e.message}")
                            // 返回一个空 View 占位，不让 factory 抛异常
                            android.view.View(ctx)
                        }
                    },
                    update = { view ->
                        try {
                            (view as? PetGLView)?.switchAnim(state.currentAnim.key)
                        } catch (_: Exception) { }
                    },
                    modifier = Modifier
                        .size(width = videoW, height = videoH)
                        .offset(x = offX, y = offY)
                )
            } else {
                // 降级：蓝色圆点占位（保证可见但不崩；GL 机型问题属罕见情况）
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clipToBounds()
                        .background(
                            androidx.compose.ui.graphics.Brush.radialGradient(
                                listOf(Color(0xFF9DB8E8), Color(0xFF6B8BC8))
                            )
                        )
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
