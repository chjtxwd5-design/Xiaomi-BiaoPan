package com.watchface.idtool.pet

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/**
 * 桌宠物理引擎 —— 从 dsh-pet 的 src/shared/physics.ts 逐函数移植。
 *
 * 坐标系：全部为「宠物包围盒左上角」的像素坐标。
 * 抛掷物理参数（重力 / 弹性 / 地面摩擦 / 顶部反弹 / 总力度）来自 [PhysicsParams]。
 */
object PetPhysics {

    // ---- 拖拽弹簧 ----
    /** 拖拽弹簧刚度：越大跟手越紧 */
    const val SPRING_K = 200f
    /** 拖拽弹簧阻尼：ζ = c/(2√k) ≈ 1.06，过阻尼，不 overshoot */
    const val SPRING_C = 30f

    // ---- 轨迹采样窗口 ----
    const val TRAIL_KEEP_MS = 200L
    const val RELEASE_WINDOW_MS = 150L
    const val RELEASE_STALE_MS = 150L
    const val MIN_SPAN_MS = 20L
    const val SEG_MIN_DT_MS = 8L

    // ---- 抛掷 ----
    const val DEAD_ZONE_SPEED = 500f
    const val MAX_THROW_SPEED = 3600f
    const val PEAK_WEIGHT = 0.5f
    const val ACCEL_REF = 8000f
    const val ACCEL_GAIN_MAX = 0.6f

    const val GRAVITY = 1400f
    const val RESTITUTION = 0.78f
    const val GROUND_FRICTION = 2.5f

    const val REST_VY = 40f
    const val REST_VX = 15f
    const val MAX_STEP_DT = 0.05f

    // ---- Q 弹挤压 ----
    const val SQ_SQUASH = 0.55f
    const val SQ_DURATION_MS = 220f
    const val SQ_SOFT_SPEED = 300f
    const val SQ_HARD_SPEED = 1500f
    const val SQ_MAX_SQUASH = 0.55f

    // ---- 积分细分 ----
    const val SUBSTEP_PX = 8f
    const val SUBSTEP_MAX = 64

    // ---- 多宠碰撞 ----
    const val PET_BOUNCE_E = 0.995f

    const val THROW_SPEED_ANCHOR = 1200f
    const val SLINGSHOT_REF_RATIO = 0.6f

    /** 抛掷物理参数（对应 TS 的 PhysicsParams） */
    data class PhysicsParams(
        val gravity: Float = GRAVITY,
        val restitution: Float = RESTITUTION,
        val groundFriction: Float = GROUND_FRICTION,
        val ceilingBounce: Boolean = true,
        val throwPower: Float = 1f,
        val petCollision: Boolean = false,
    )

    /** 一次轨迹采样 */
    data class DragSample(val t: Long, val x: Float, val y: Float)

    /** 抛掷边界：包围盒左上角的允许活动范围 */
    data class ThrowBounds(
        val minX: Float, val minY: Float,
        val maxX: Float, val maxY: Float,
    )

    /** 抛体当前状态 */
    data class ThrowState(
        val x: Float, val y: Float,
        val vx: Float, val vy: Float,
    )

    /** throwStep 的返回：状态 + 两个标志 */
    data class StepResult(
        val state: ThrowState,
        val bounced: Boolean,
        val atRest: Boolean,
    )

    /** 角色区域宽高比（对齐 dsh-pet 视频里角色的包围盒 240×285） */
    const val ROLE_ASPECT = 240f / 285f

    /** 由 W/H/size 计算抛掷边界：身体（盒内缩 sideAllow）贴屏幕边缘才反弹 */
    fun throwBounds(W: Float, H: Float, size: Float, sideAllow: Float): ThrowBounds {
        val h = size / ROLE_ASPECT
        return ThrowBounds(
            minX = -sideAllow, minY = 0f,
            maxX = W - size + sideAllow, maxY = H - h,
        )
    }

    /** 剔除超过保留窗口的旧采样 */
    fun trimTrail(trail: List<DragSample>, now: Long): List<DragSample> {
        val cutoff = now - TRAIL_KEEP_MS
        val i = trail.indexOfFirst { it.t >= cutoff }
        return if (i <= 0) trail else trail.subList(i, trail.size)
    }

    /** 过阻尼弹簧单轴速度步进：调用方随后 x += v*dt */
    fun springStep(v: Float, x: Float, target: Float, dt: Float, power: Float = 1f): Float =
        v + ((target - x) * SPRING_K - v * SPRING_C) * power * dt

    /** 软钳速：cap*(1-e^(-s/cap))，任意速度下单调可区分、渐近不超过 cap */
    fun softClampSpeed(speed: Float): Float =
        if (speed <= 0f) 0f else MAX_THROW_SPEED * (1f - exp(-speed / MAX_THROW_SPEED))

    /** 抛掷指令（angle 度，power）→ 初速度，px/s */
    fun throwVelocity(angleDeg: Float, power: Float): Pair<Float, Float> {
        val speed = softClampSpeed(power * THROW_SPEED_ANCHOR)
        val rad = angleDeg * Math.PI.toFloat() / 180f
        return cos(rad) * speed to -sin(rad) * speed
    }

    /** 「一倍力度」的拉距 */
    fun slingshotRefDist(size: Float): Float =
        if (size > 0f) size * SLINGSHOT_REF_RATIO else 0f

    /** 拉距 → 力度倍率：线性、单调、不夹取 */
    fun slingshotPower(dist: Float, size: Float): Float {
        val ref = slingshotRefDist(size)
        if (ref <= 0f || dist <= 0f || !dist.isFinite()) return 0f
        return dist / ref
    }

    /** 拉向量 → 弹射角度（弹的是拉的反方向） */
    fun pullAngle(pullX: Float, pullY: Float): Float =
        (kotlin.math.atan2(pullY, -pullX) * 180f / Math.PI.toFloat())

    /** 弹弓初速：无软上限 */
    fun slingshotVelocity(angleDeg: Float, power: Float, throwPower: Float = 1f): Pair<Float, Float> {
        val speed = THROW_SPEED_ANCHOR * max(0f, power) * max(0f, throwPower)
        if (speed == 0f) return 0f to 0f
        val rad = angleDeg * Math.PI.toFloat() / 180f
        return cos(rad) * speed to -sin(rad) * speed
    }

    /**
     * 由拖拽轨迹估算松手初速。返回 null = 温柔放下（不抛）。
     */
    fun estimateReleaseVelocity(
        trail: List<DragSample>,
        now: Long,
        physics: PhysicsParams = PhysicsParams(),
    ): Pair<Float, Float>? {
        if (trail.isEmpty()) return null
        val last = trail.last()
        if (now - last.t > RELEASE_STALE_MS) return null

        val win = trail.filter { now - it.t <= RELEASE_WINDOW_MS }
        if (win.size < 2) return null

        val t0 = win.first().t
        val x0 = win.first().x
        val y0 = win.first().y
        val t1 = win.last().t
        val x1 = win.last().x
        val y1 = win.last().y
        val spanMs = t1 - t0
        if (spanMs < MIN_SPAN_MS) return null

        val baseVx = (x1 - x0) / spanMs * 1000f
        val baseVy = (y1 - y0) / spanMs * 1000f
        val baseSpeed = hypot(baseVx, baseVy)
        if (baseSpeed < 1e-6f) return null

        // 分段速度
        val segSpeeds = mutableListOf<Pair<Float, Long>>() // speed, tEnd
        var px = x0; var py = y0; var pt = t0
        for (i in 1 until win.size) {
            val s = win[i]
            val dt = s.t - pt
            if (dt >= SEG_MIN_DT_MS) {
                segSpeeds.add((hypot(s.x - px, s.y - py) / dt * 1000f) to s.t)
                px = s.x; py = s.y; pt = s.t
            }
        }
        val peakSpeed = if (segSpeeds.isNotEmpty()) segSpeeds.maxOf { it.first } else baseSpeed

        // 末段加速度
        var accel = 0f
        if (segSpeeds.size >= 2) {
            val lastSeg = segSpeeds.last()
            val firstSeg = segSpeeds.first()
            accel = (lastSeg.first - firstSeg.first) /
                    max((lastSeg.second - firstSeg.second) / 1000f, MIN_SPAN_MS / 1000f)
        }

        val speedBeforeClamp =
            ((1f - PEAK_WEIGHT) * baseSpeed + PEAK_WEIGHT * peakSpeed) *
                    (1f + min(max(accel, 0f) / ACCEL_REF, 1f) * ACCEL_GAIN_MAX)
        val speed = softClampSpeed(speedBeforeClamp) * physics.throwPower
        if (speed < DEAD_ZONE_SPEED) return null

        return (baseVx / baseSpeed * speed) to (baseVy / baseSpeed * speed)
    }

    /**
     * 抛体单步积分 + 边界反弹。
     * bounced = 本步是否碰边/落地；atRest = 贴地且低速（或碰边后整体低速），调用方应停止循环。
     */
    fun throwStep(
        s: ThrowState,
        dtRaw: Float,
        b: ThrowBounds,
        physics: PhysicsParams = PhysicsParams(),
    ): StepResult {
        val dt = min(max(dtRaw, 0f), MAX_STEP_DT)
        var x = s.x; var y = s.y; var vx = s.vx; var vy = s.vy
        vy += physics.gravity * dt
        x += vx * dt
        y += vy * dt
        var bounced = false

        if (x < b.minX) {
            x = b.minX
            vx = abs(vx) * physics.restitution
            bounced = true
        } else if (x > b.maxX) {
            x = b.maxX
            vx = -abs(vx) * physics.restitution
            bounced = true
        }
        if (y < b.minY) {
            // ceilingBounce=false：顶部无边界，不夹不弹
            if (physics.ceilingBounce) {
                y = b.minY
                vy = abs(vy) * physics.restitution
                bounced = true
            }
        } else if (y >= b.maxY) {
            y = b.maxY
            vx *= max(0f, 1f - physics.groundFriction * dt)
            vy = if (abs(vy) < REST_VY) 0f else -abs(vy) * physics.restitution
            bounced = true
        }

        val speed = hypot(vx, vy)
        val atRest =
            (y >= b.maxY - 1f && abs(vy) < 1f && abs(vx) < REST_VX) ||
                    (bounced && speed < REST_VY && abs(vy) < 1f)
        return StepResult(ThrowState(x, y, vx, vy), bounced, atRest)
    }

    /** 落地冲击速度 → 下压幅度 scaleY 值（速度越大压得越狠） */
    fun landingSquash(impactSpeed: Float): Float {
        val t = min(max((abs(impactSpeed) - SQ_SOFT_SPEED) / (SQ_HARD_SPEED - SQ_SOFT_SPEED), 0f), 1f)
        return min(0.8f, 1f - t * (1f - SQ_MAX_SQUASH))
    }

    /**
     * Q 弹挤压曲线：u∈[0,1] 进度 → scaleY 值。
     * 下压段（0~0.45）ease-in 压缩；回弹段（0.45~1）easeOutBack 带回弹过冲（~4%）。
     */
    fun squashScale(u: Float, squash: Float = SQ_SQUASH): Float {
        if (u < 0.45f) {
            val p = u / 0.45f
            return 1f - (1f - squash) * p * p
        }
        val p = (u - 0.45f) / 0.55f
        val c1 = 1.70158f
        val c3 = c1 + 1f
        val f = 1f + c3 * (p - 1f).pow(3) + c1 * (p - 1f).pow(2)
        return min(1.12f, squash + (1f - squash) * max(f, 0f))
    }

    /** 一帧该积几步（整数 ≥ 1） */
    fun throwSubsteps(vx: Float, vy: Float, dt: Float): Int {
        val travel = hypot(vx, vy) * min(max(dt, 0f), MAX_STEP_DT)
        if (travel <= SUBSTEP_PX) return 1
        return min(SUBSTEP_MAX, ceil(travel / SUBSTEP_PX).toInt())
    }
}
