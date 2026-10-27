package com.watchface.idtool.pet

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

/**
 * 桌宠渲染层 —— 双模式：
 *
 * 1. 安全模式（PetStorage.videoEnabled=false，默认）：
 *    Compose 静态形象 + 呼吸动画 + Q 弹挤压。零 GL 代码，绝对不崩。
 * 2. 视频模式（设置里开启「动态视频」）：
 *    PetGLView GL 色键透明渲染 106 个动画（实验功能）。
 *
 * 交互通用：单击 → 点击回应；长按 → 菜单；拖拽 → 弹簧跟手 + 甩抛物理。
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

        // 呼吸动画（安全模式的"动感"来源）
        val breath = rememberInfiniteTransition(label = "petBreath")
        val breathScale by breath.animateFloat(
            initialValue = 1f,
            targetValue = 1.04f,
            animationSpec = infiniteRepeatable(tween(1400), RepeatMode.Reverse),
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
                // ---- 视频模式（实验功能，设置里开启）----
                AndroidView(
                    factory = { ctx ->
                        try {
                            PetGLView(ctx, state.currentAnim.key) { }
                        } catch (e: Exception) {
                            android.util.Log.e("PetSprite", "GLView create failed: ${e.message}")
                            android.view.View(ctx)
                        }
                    },
                    update = { view ->
                        try {
                            (view as? PetGLView)?.switchAnim(state.currentAnim.key)
                        } catch (_: Exception) { }
                    },
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
                            alpha = 0.96f
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
