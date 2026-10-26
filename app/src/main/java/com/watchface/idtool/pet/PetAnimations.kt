package com.watchface.idtool.pet

import android.content.Context
import org.json.JSONObject
import kotlin.random.Random

/**
 * 桌宠动画库 —— 运行时从 assets/pet/animations.json 动态加载全部 106 个动画。
 *
 * 为什么不硬编码文件名：转码产物的命名与代码解耦，增删动画只需重新生成 JSON，
 * 无需改动 Kotlin（也避免了之前「Kotlin 写死 98 个名字 vs 转码产物另一套名字」的冲突）。
 *
 * 分类体系（JSON 的 category 字段）：
 *   idle / turn / move / action / balance / work / whisper
 * 随机动作从 action + idle 池里按权重抽取；balance 按已用百分比分档；work/whisper 由事件触发。
 */
object PetAnimations {

    /** 基准宠物宽（px）：移动距离按 实际size/REF 等比缩放 */
    const val PET_REF_WIDTH = 462f

    /** 单个动画的元数据（对应 JSON 的一条） */
    data class Anim(
        /** 资源 key（文件名不含扩展名，assets/pet/<key>.mp4） */
        val key: String,
        /** 中文显示名 */
        val name: String,
        /** 分类：idle/turn/move/action/balance/work/whisper */
        val category: String,
        /** 时长 ms */
        val durationMs: Long,
        /** 文件体积 bytes */
        val bytes: Long,
    )

    /** 移动动画的参数 */
    data class MoveParams(
        val minDist: Int = 60,
        val maxDist: Int = 240,
        val margin: Int = 20,
        val leadSec: Float = 2f,
        val tailSec: Float = 2f,
    )

    // ==================== 运行时加载 ====================

    @Volatile
    private var loaded = false

    /** 全部动画（按 key 索引） */
    private val allAnims = mutableListOf<Anim>()

    /** 按分类分组的动画（分类 id → 列表） */
    private val byCategory = mutableMapOf<String, MutableList<Anim>>()

    /** 动画是否已加载成功 */
    val isLoaded: Boolean get() = loaded

    /** 动画总数 */
    val totalCount: Int get() = allAnims.size

    /**
     * 从 assets/pet/animations.json 加载全部动画。
     * 必须在 UI 使用前调用（建议在主界面 init 或 Application.onCreate 里调一次）。
     * 解析失败时保持空列表，UI 层需容错。
     */
    fun load(context: Context) {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            try {
                val json = context.assets.open("pet/animations.json")
                    .bufferedReader(Charsets.UTF_8).use { it.readText() }
                val root = JSONObject(json)
                val arr = root.optJSONArray("animations") ?: return
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val key = o.optString("key", "")
                    if (key.isEmpty()) continue
                    val anim = Anim(
                        key = key,
                        name = o.optString("name", key),
                        category = o.optString("category", "action"),
                        durationMs = o.optLong("durationMs", 3000L),
                        bytes = o.optLong("fileSizeBytes", 0L),
                    )
                    allAnims.add(anim)
                    byCategory.getOrPut(anim.category) { mutableListOf() }.add(anim)
                }
                loaded = allAnims.isNotEmpty()
                android.util.Log.i("PetAnims", "loaded ${allAnims.size} animations, " +
                        byCategory.entries.joinToString { "${it.key}=${it.value.size}" })
            } catch (e: Exception) {
                android.util.Log.e("PetAnims", "load failed: ${e.message}")
            }
        }
    }

    /** 按分类取动画列表（空分类返回空列表） */
    fun of(category: String): List<Anim> = byCategory[category] ?: emptyList()

    /** 全部动画（只读） */
    fun all(): List<Anim> = allAnims

    /** 按 key 查找 */
    fun find(key: String): Anim? = allAnims.firstOrNull { it.key == key }

    // ==================== 分类池（供状态机使用） ====================

    /** 待机动画（idle-breathe 必须排在 idle 池第一条） */
    fun idleAnim(): Anim =
        of("idle").firstOrNull() ?: allAnims.firstOrNull() ?: FALLBACK_IDLE

    /** 转向动画 */
    fun turnAnim(): Anim =
        of("turn").firstOrNull() ?: idleAnim()

    /** 拖拽悬空动画（drag 分类：被无形抓起悬空） */
    fun dragAnim(): Anim =
        of("drag").firstOrNull() ?: idleAnim()

    /** 点击回应池（转码产物把 5 个 click-* 放在 idle 分类里，按 key 前缀识别） */
    fun clickAnims(): List<Anim> =
        allAnims.filter { it.key.startsWith("click-") || it.name.contains("点击回应") }
            .ifEmpty { of("action").take(5) }
            .ifEmpty { listOf(idleAnim()) }

    /** 移动动画池 */
    fun moveAnims(): List<Anim> = of("move").ifEmpty { listOf(idleAnim()) }

    /** 随机动作池（action 分类全部） */
    fun actionAnims(): List<Anim> = of("action").ifEmpty { of("idle") }

    /** 余额档位动画 */
    fun balanceAnims(): List<Anim> = of("balance")

    /** 工作状态动画 */
    fun workAnims(): List<Anim> = of("work")

    /** 碎碎念动画 */
    fun whisperAnims(): List<Anim> = of("whisper")

    /** 按已用百分比取余额档位动画（p<20/40/60/80/<100/==100 六档） */
    fun balanceAnimFor(usedPercent: Int): Anim {
        val pool = balanceAnims()
        if (pool.isEmpty()) return idleAnim()
        val idx = if (usedPercent >= 100) 5 else (usedPercent / 20).coerceIn(0, 4)
        return pool.getOrNull(idx) ?: pool.first()
    }

    /** 随机选一个动作 */
    fun randomAction(rnd: Random = Random.Default): Anim {
        val pool = actionAnims()
        return pool.randomOrNull(rnd) ?: idleAnim()
    }

    /** 随机选一个点击回应 */
    fun randomClick(rnd: Random = Random.Default): Anim {
        val pool = clickAnims()
        return pool.randomOrNull(rnd) ?: idleAnim()
    }

    /** 随机选一个移动动画 */
    fun randomMove(rnd: Random = Random.Default): Pair<Anim, MoveParams> {
        val pool = moveAnims()
        val anim = pool.randomOrNull(rnd) ?: idleAnim()
        // 移动参数按动画类型区分（螃蟹走路小步、左转奔跑大步）
        val params = when {
            anim.key.contains("crab") -> MoveParams()
            anim.key.contains("float") -> MoveParams(minDist = 40, maxDist = 120)
            anim.key.contains("run") -> MoveParams(minDist = 120, maxDist = 320, leadSec = 1.75f, tailSec = 4.8f)
            else -> MoveParams()
        }
        return anim to params
    }

    /** 兜底动画（JSON 加载失败时使用，指向一个必然存在的空壳） */
    private val FALLBACK_IDLE = Anim("idle-breathe", "待机呼吸休闲", "idle", 4000L, 0L)
}
