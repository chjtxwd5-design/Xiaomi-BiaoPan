package com.watchface.idtool.pet

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * 桌宠余额查询 —— 对应 dsh-pet 的 balance/providers/deepseek-official.ts。
 *
 * 调用 DeepSeek 余额接口，返回可用余额；按「已用百分比」映射到 6 档余额动画。
 * 注意：余额接口返回的是**剩余金额**，「已用百分比」需要用户设定初始预算才能算出；
 *       若未设初始预算，则按余额绝对值粗分档（余额越低越紧张）。
 */
object PetBalanceApi {

    private const val ENDPOINT = "https://api.deepseek.com/user/balance"
    private const val TIMEOUT_MS = 20_000

    /** 余额信息 */
    data class BalanceInfo(
        val available: Boolean,
        /** 总余额（按 currency 单位） */
        val totalBalance: Double,
        /** 赠予余额 */
        val grantedBalance: Double,
        /** 充值余额 */
        val toppedUpBalance: Double,
        val currency: String,
    ) {
        /** 余额紧张度（0=充裕，100=告急）：用于选择动画档位 */
        fun tightnessPercent(initialBudget: Double): Int {
            if (initialBudget <= 0.0) {
                // 未设预算：按绝对余额粗分（>=50 充裕，<1 告急）
                return when {
                    totalBalance >= 50 -> 0
                    totalBalance >= 20 -> 20
                    totalBalance >= 10 -> 40
                    totalBalance >= 3 -> 60
                    totalBalance > 0 -> 85
                    else -> 100
                }
            }
            val used = ((initialBudget - totalBalance) / initialBudget * 100).toInt()
            return used.coerceIn(0, 100)
        }
    }

    sealed class Result {
        data class Success(val info: BalanceInfo) : Result()
        data class Failure(val message: String) : Result()
    }

    /**
     * 查询余额。
     * @param apiKey DeepSeek API Key（与闲聊共用）
     */
    suspend fun query(apiKey: String): Result = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) {
            return@withContext Result.Failure("未设置 API Key")
        }
        try {
            val conn = (URL(ENDPOINT).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                setRequestProperty("Authorization", "Bearer $apiKey")
                setRequestProperty("Accept", "application/json")
            }
            try {
                val code = conn.responseCode
                if (code !in 200..299) {
                    try { conn.errorStream?.close() } catch (_: Exception) { }
                    return@withContext Result.Failure(
                        when (code) {
                            401 -> "API Key 无效"
                            429 -> "请求太频繁"
                            else -> "服务返回 $code"
                        }
                    )
                }
                val text = conn.inputStream.bufferedReader().use { it.readText() }
                val root = JSONObject(text)

                val isAvailable = root.optBoolean("is_available", true)
                val infos = root.optJSONArray("balance_infos")
                if (infos == null || infos.length() == 0) {
                    return@withContext Result.Failure("余额数据为空")
                }
                val info = infos.optJSONObject(0) ?: return@withContext Result.Failure("余额数据格式错误")

                Result.Success(
                    BalanceInfo(
                        available = isAvailable,
                        totalBalance = info.optString("total_balance", "0").toDoubleOrNull() ?: 0.0,
                        grantedBalance = info.optString("granted_balance", "0").toDoubleOrNull() ?: 0.0,
                        toppedUpBalance = info.optString("topped_up_balance", "0").toDoubleOrNull() ?: 0.0,
                        currency = info.optString("currency", "CNY"),
                    )
                )
            } finally {
                conn.disconnect()
            }
        } catch (e: java.net.SocketTimeoutException) {
            Result.Failure("查询超时")
        } catch (e: Exception) {
            Result.Failure("查询失败：${e.message ?: e.javaClass.simpleName}")
        }
    }
}
