package com.watchface.idtool.pet

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.min
import kotlin.random.Random

/**
 * 桌宠行为状态机 —— 移植 dsh-pet 的 shared/state.ts 行为逻辑。
 *
 * 状态机：
 *   IDLE    待机（呼吸动画，累计静止时长）
 *   ACTION  播放随机动作（播完回 IDLE）
 *   MOVE    移动（走路动画 + 位移）
 *   DRAG    被拖拽（悬空动画，跟手弹簧）
 *   THROW   抛掷飞行（物理步进）
 *   SQUASH  落地/点击 Q 弹挤压
 *
 * 行为决策（对应 rollKind/pickNext）：
 *   权重 idle=10 / turn=5 / move=5，剩余 80% 走随机动作分类
 */
class PetState(
    /** 宠物显示宽度（px） */
    initialSize: Float = 180f,
    /** 屏幕宽高 */
    private val screenW: Float,
    private val screenH: Float,
    private val random: Random = Random.Default,
) {

    /** 行为状态 */
    enum class Mode { IDLE, ACTION, MOVE, DRAG, THROW, SQUASH }

    var mode by mutableStateOf(Mode.IDLE)
        private set

    /** 当前播放的动画 */
    var currentAnim by mutableStateOf(PetAnimations.idleAnim())
        private set

    /** 朝向：true = 面向右 */
    var facingRight by mutableStateOf(true)
        private set

    /** 位置（包围盒左上角 px） */
    var x by mutableFloatStateOf(screenW / 2f - initialSize / 2f)
        private set
    var y by mutableFloatStateOf(screenH * 0.6f)
        private set

    /** 速度（抛掷/移动用）*/
    var vx by mutableFloatStateOf(0f)
        private set
    var vy by mutableFloatStateOf(0f)
        private set

    /** 尺寸 */
    var size by mutableFloatStateOf(initialSize)
        private set

    /** Q 弹挤压：scaleY 值 */
    var squashScaleY by mutableFloatStateOf(1f)
        private set
    var squashScaleX by mutableFloatStateOf(1f)
        private set

    /** 累计静止时长（ms），用于触发随机行为 */
    private var idleAccumMs = 0L

    /** 对话气泡文本（null = 不显示） */
    var bubbleText by mutableStateOf<String?>(null)
        private set

    /** 显示气泡（自动消失由 UI 层处理） */
    fun showBubble(text: String) {
        bubbleText = text
    }

    /** 清除气泡 */
    fun clearBubble() {
        bubbleText = null
    }

    /** 当前动作剩余时长（ms） */
    private var actionRemainMs = 0L

    /** 移动目标 x */
    private var moveTargetX = 0f
    private var moveOriginX = 0f
    private var moveProgress = 0f
    private var moveDurationMs = 0L
    private var moveElapsedMs = 0L

    /** 拖拽 */
    private var dragTargetX = 0f
    private var dragTargetY = 0f

    /** 挤压动画进度 0..1 */
    private var squashProgress = 1f
    private var squashStartScale = 1f

    /** 抛掷边界 */
    private val bounds get() = PetPhysics.throwBounds(screenW, screenH, size, sideAllow = 0f)

    /** 落地 / 静止判定 */
    val isAtRest: Boolean get() = mode == Mode.THROW && abs(vy) < 1f && abs(vx) < 15f && y >= bounds.maxY - 1f

    // ==================== 行为决策 ====================

    /** 掷骰决定下一个行为（对应 rollKind） */
    private fun rollNextMode(): Mode {
        val r = random.nextInt(100)
        return when {
            r < 10 -> Mode.IDLE                              // 10% 待机
            r < 15 -> Mode.ACTION                            // 5% 转向动画（简化合并）
            r < 20 -> Mode.MOVE                              // 5% 移动
            else -> Mode.ACTION                              // 80% 随机动作
        }
    }

    /** 从动态加载的动作池选一个随机动作 */
    private fun pickRandomAction(): PetAnimations.Anim =
        PetAnimations.randomAction(random)

    /** 启动一个动作 */
    fun playAction(anim: PetAnimations.Anim, durationMs: Long = anim.durationMs) {
        currentAnim = anim
        mode = Mode.ACTION
        actionRemainMs = durationMs
    }

    /** 显式播放点名动画（右键菜单/事件触发） */
    fun playNamed(anim: PetAnimations.Anim) = playAction(anim)

    /** 播放点击回应 */
    fun onClickReaction() {
        val anim = PetAnimations.randomClick(random)
        playAction(anim)
        startSquash(PetPhysics.SQ_SQUASH)
    }

    /** 开始移动 */
    fun startMove() {
        val (anim, params) = PetAnimations.randomMove(random)
        val scale = size / PetAnimations.PET_REF_WIDTH
        val dist = (params.minDist + random.nextInt(params.maxDist - params.minDist)) * scale
        val dir = if (random.nextBoolean()) 1f else -1f
        moveOriginX = x
        moveTargetX = (x + dir * dist).coerceIn(bounds.minX, bounds.maxX)
        facingRight = moveTargetX > x
        moveDurationMs = anim.durationMs
        moveElapsedMs = 0L
        currentAnim = anim
        mode = Mode.MOVE
    }

    /** 开始拖拽 */
    fun startDrag() {
        mode = Mode.DRAG
        currentAnim = PetAnimations.dragAnim()
        vx = 0f
        vy = 0f
        dragTargetX = x
        dragTargetY = y
    }

    /** 拖拽跟手（目标位置） */
    fun updateDragTarget(tx: Float, ty: Float) {
        dragTargetX = tx.coerceIn(bounds.minX, bounds.maxX)
        dragTargetY = ty.coerceIn(bounds.minY, bounds.maxY)
    }

    /** 松手：估速抛掷 */
    fun releaseDrag(trail: List<PetPhysics.DragSample>, nowMs: Long) {
        val vel = PetPhysics.estimateReleaseVelocity(trail, nowMs)
        if (vel == null) {
            // 温柔放下：回待机
            mode = Mode.IDLE
            currentAnim = PetAnimations.idleAnim()
            idleAccumMs = 0L
        } else {
            vx = vel.first
            vy = vel.second
            mode = Mode.THROW
            currentAnim = PetAnimations.dragAnim()
        }
    }

    /** 启动 Q 弹 */
    private fun startSquash(squash: Float) {
        squashStartScale = squash
        squashProgress = 0f
        squashScaleY = squash
        squashScaleX = 1f + (1f - squash) * 0.5f
    }

    // ==================== 每帧更新 ====================

    /**
     * 步进一帧。
     * @param dtMs 帧间隔（ms）
     */
    fun tick(dtMs: Long) {
        val dt = dtMs / 1000f
        when (mode) {
            Mode.IDLE -> {
                idleAccumMs += dtMs
                // 待机超过 4 秒触发下一个行为
                if (idleAccumMs > 4000) {
                    idleAccumMs = 0
                    when (rollNextMode()) {
                        Mode.MOVE -> startMove()
                        else -> playAction(pickRandomAction())
                    }
                }
            }
            Mode.ACTION -> {
                actionRemainMs -= dtMs
                if (actionRemainMs <= 0) {
                    mode = Mode.IDLE
                    currentAnim = PetAnimations.idleAnim()
                    idleAccumMs = 0
                }
            }
            Mode.MOVE -> {
                moveElapsedMs += dtMs
                moveProgress = min(1f, moveElapsedMs.toFloat() / moveDurationMs.coerceAtLeast(1))
                x = moveOriginX + (moveTargetX - moveOriginX) * easeInOut(moveProgress)
                if (moveProgress >= 1f) {
                    mode = Mode.IDLE
                    currentAnim = PetAnimations.idleAnim()
                    idleAccumMs = 0
                }
            }
            Mode.DRAG -> {
                // 跟手弹簧（每帧两轴独立）
                vx = PetPhysics.springStep(vx, x, dragTargetX, min(dt, 0.05f))
                vy = PetPhysics.springStep(vy, y, dragTargetY, min(dt, 0.05f))
                x += vx * dt
                y += vy * dt
            }
            Mode.THROW -> {
                // 物理步进（可能多子步）
                val steps = PetPhysics.throwSubsteps(vx, vy, dt)
                var state = PetPhysics.ThrowState(x, y, vx, vy)
                var result: PetPhysics.StepResult? = null
                repeat(steps) {
                    result = PetPhysics.throwStep(state, dt / steps, bounds)
                    state = result!!.state
                }
                x = state.x
                y = state.y
                vx = state.vx
                vy = state.vy
                // 落地触发 Q 弹
                if (result?.bounced == true && result!!.state.vy > 0) {
                    startSquash(PetPhysics.landingSquash(abs(result!!.state.vy)))
                }
                if (result?.atRest == true) {
                    mode = Mode.IDLE
                    currentAnim = PetAnimations.idleAnim()
                    idleAccumMs = 0
                }
            }
            Mode.SQUASH -> Unit // 挤压是叠加态，由下面独立推进
        }

        // Q 弹挤压推进（独立于 mode）
        if (squashProgress < 1f) {
            squashProgress = min(1f, squashProgress + dtMs / PetPhysics.SQ_DURATION_MS)
            squashScaleY = PetPhysics.squashScale(squashProgress, squashStartScale)
            squashScaleX = 1f + (1f - squashScaleY) * 0.5f
        }
    }

    /** 缓动：easeInOutQuad */
    private fun easeInOut(t: Float): Float =
        if (t < 0.5f) 2f * t * t else 1f - (-2f * t + 2f) * (-2f * t + 2f) / 2f

    /** 设置尺寸 */
    fun resize(newSize: Float) {
        size = newSize.coerceIn(80f, 400f)
        x = x.coerceIn(bounds.minX, bounds.maxX)
        y = y.coerceIn(bounds.minY, bounds.maxY)
    }

    /** 屏幕尺寸变化时重设边界 */
    fun onScreenChanged() {
        x = x.coerceIn(bounds.minX, bounds.maxX)
        y = y.coerceIn(bounds.minY, bounds.maxY)
    }

    /** 回到初始位置（右键菜单「回到初始位置」） */
    fun resetPosition() {
        x = screenW / 2f - size / 2f
        y = screenH * 0.6f
        vx = 0f
        vy = 0f
        mode = Mode.IDLE
        currentAnim = PetAnimations.idleAnim()
    }

    /** 设置到指定位置（恢复上次位置用） */
    fun setPosition(nx: Float, ny: Float) {
        x = nx.coerceIn(bounds.minX, bounds.maxX)
        y = ny.coerceIn(bounds.minY, bounds.maxY)
    }

    /** 翻转朝向 */
    fun flipFacing() {
        facingRight = !facingRight
    }
}
