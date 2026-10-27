package com.watchface.idtool.pet

import android.view.View
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlin.math.roundToInt

/** 角色区域宽高比（宽/高）：源视频 640x360 里角色占 240x285 */
const val PET_ROLE_ASPECT = 240f / 285f

/** 视频里角色包围盒（640×360 基准，对齐 dsh-pet HIT_BOX） */
private const val ROLE_X0 = 200f
private const val ROLE_Y0 = 50f
private const val ROLE_X1 = 440f
private const val ROLE_Y1 = 335f
private const val VIDEO_W = 640f
private const val VIDEO_H = 360f

/**
 * 桌宠渲染层 —— SurfaceView 直接播放源 WebM（VP9-alpha，系统原生透明）。
 *
 * 尺寸：视频 640×360 里角色占 240×285，放大 2.667 倍并偏移让角色填满 View。
 * 交互：单击 → 点击回应动画 + Q 弹；长按 → 菜单；拖拽 → 弹簧跟手 + 甩抛物理。
 */
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

        // 视频放大 + 偏移（角色区域填满 View）
        val scale = VIDEO_W / (ROLE_X1 - ROLE_X0)
        val videoW = (state.size * scale).dp
        val videoH = (state.size * scale * VIDEO_H / VIDEO_W).dp
        val offX = (-(ROLE_X0 / (ROLE_X1 - ROLE_X0)) * state.size).dp
        val offY = (-(ROLE_Y0 / (ROLE_Y1 - ROLE_Y0)) * state.size).dp

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
            // SurfaceView 播 WebM-alpha（系统原生透明合成）
            AndroidView(
                factory = { ctx: android.content.Context ->
                    try {
                        PetWebmView(ctx, state.currentAnim.key)
                    } catch (e: Exception) {
                        android.util.Log.e("PetSprite", "webm view failed: ${e.message}")
                        View(ctx)
                    }
                },
                update = { view: View ->
                    try {
                        (view as? PetWebmView)?.playAnim(state.currentAnim.key)
                    } catch (_: Exception) { }
                },
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
