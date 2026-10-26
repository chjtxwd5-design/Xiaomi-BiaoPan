package com.watchface.idtool.pet

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * 桌宠配置与记忆持久化。
 *
 * 存储项（SharedPreferences: "pet_settings"）：
 *   · api_key            DeepSeek API Key（闲聊与余额共用）
 *   · chat_enabled       对话功能开关
 *   · whisper_enabled    碎碎念开关
 *   · balance_enabled    余额查询开关
 *   · persona            自定义人设（留空用默认）
 *   · size               宠物显示宽度（dp）
 *   · pos_x / pos_y      最近位置
 *   · initial_budget     初始预算（用于算已用百分比）
 *   · memory             对话记忆（JSON 数组，最多 50 条）
 */
object PetStorage {

    private const val PREFS = "pet_settings"

    private const val KEY_API = "api_key"
    private const val KEY_CHAT = "chat_enabled"
    private const val KEY_WHISPER = "whisper_enabled"
    private const val KEY_BALANCE = "balance_enabled"
    private const val KEY_PERSONA = "persona"
    private const val KEY_SIZE = "size"
    private const val KEY_POS_X = "pos_x"
    private const val KEY_POS_Y = "pos_y"
    private const val KEY_BUDGET = "initial_budget"
    private const val KEY_MEMORY = "memory"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ---------- API Key ----------
    fun getApiKey(context: Context): String = prefs(context).getString(KEY_API, "") ?: ""

    fun setApiKey(context: Context, key: String) {
        prefs(context).edit().putString(KEY_API, key.trim()).apply()
    }

    // ---------- 功能开关 ----------
    var chatEnabled: Boolean = false
        private set
    var whisperEnabled: Boolean = true
        private set
    var balanceEnabled: Boolean = true
        private set

    fun loadSwitches(context: Context) {
        val p = prefs(context)
        chatEnabled = p.getBoolean(KEY_CHAT, false)
        whisperEnabled = p.getBoolean(KEY_WHISPER, true)
        balanceEnabled = p.getBoolean(KEY_BALANCE, true)
    }

    fun setChatEnabled(context: Context, enabled: Boolean) {
        chatEnabled = enabled
        prefs(context).edit().putBoolean(KEY_CHAT, enabled).apply()
    }

    fun setWhisperEnabled(context: Context, enabled: Boolean) {
        whisperEnabled = enabled
        prefs(context).edit().putBoolean(KEY_WHISPER, enabled).apply()
    }

    fun setBalanceEnabled(context: Context, enabled: Boolean) {
        balanceEnabled = enabled
        prefs(context).edit().putBoolean(KEY_BALANCE, enabled).apply()
    }

    // ---------- 人设 ----------
    fun getPersona(context: Context): String =
        prefs(context).getString(KEY_PERSONA, "")?.ifBlank { PetChatApi.DEFAULT_PERSONA }
            ?: PetChatApi.DEFAULT_PERSONA

    fun setPersona(context: Context, persona: String) {
        prefs(context).edit().putString(KEY_PERSONA, persona.trim()).apply()
    }

    // ---------- 尺寸与位置 ----------
    fun getSize(context: Context): Float = prefs(context).getFloat(KEY_SIZE, 180f)

    fun setSize(context: Context, size: Float) {
        prefs(context).edit().putFloat(KEY_SIZE, size).apply()
    }

    fun getPosition(context: Context): Pair<Float, Float>? {
        val p = prefs(context)
        if (!p.contains(KEY_POS_X)) return null
        return p.getFloat(KEY_POS_X, 0f) to p.getFloat(KEY_POS_Y, 0f)
    }

    fun setPosition(context: Context, x: Float, y: Float) {
        prefs(context).edit().putFloat(KEY_POS_X, x).putFloat(KEY_POS_Y, y).apply()
    }

    // ---------- 初始预算 ----------
    fun getInitialBudget(context: Context): Double =
        prefs(context).getFloat(KEY_BUDGET, 0f).toDouble()

    fun setInitialBudget(context: Context, budget: Double) {
        prefs(context).edit().putFloat(KEY_BUDGET, budget.toFloat()).apply()
    }

    // ---------- 对话记忆 ----------
    /** 读取最近 N 轮记忆（最多 50 条） */
    fun getMemory(context: Context): List<PetChatApi.Message> {
        val json = prefs(context).getString(KEY_MEMORY, "[]") ?: "[]"
        return try {
            val arr = JSONArray(json)
            buildList {
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    add(
                        PetChatApi.Message(
                            role = o.optString("role", "user"),
                            content = o.optString("content", "")
                        )
                    )
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    /** 追加一条记忆（超过 50 条自动裁掉最早的） */
    fun appendMemory(context: Context, role: String, content: String) {
        val list = getMemory(context).toMutableList()
        list.add(PetChatApi.Message(role, content))
        while (list.size > 50) list.removeAt(0)
        val arr = JSONArray()
        list.forEach {
            arr.put(JSONObject().apply {
                put("role", it.role)
                put("content", it.content)
            })
        }
        prefs(context).edit().putString(KEY_MEMORY, arr.toString()).apply()
    }

    /** 取最近 N 轮（1 轮 = 1 问 1 答 = 2 条消息） */
    fun recentMemory(context: Context, rounds: Int = 5): List<PetChatApi.Message> {
        val all = getMemory(context)
        val take = rounds * 2
        return if (all.size <= take) all else all.subList(all.size - take, all.size)
    }

    fun clearMemory(context: Context) {
        prefs(context).edit().putString(KEY_MEMORY, "[]").apply()
    }

    fun clearAll(context: Context) {
        prefs(context).edit().clear().apply()
        chatEnabled = false
        whisperEnabled = true
        balanceEnabled = true
    }
}
