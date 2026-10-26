package com.watchface.idtool.pet

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

/**
 * 桌宠 AI 闲聊 —— 对应 dsh-pet 的 shared/chat.ts + whisper.ts。
 *
 * 功能：
 *   · 对话（用户输入 → 宠物回复，带多轮上下文）
 *   · 碎碎念（无人输入时，定期自动生成一句话）
 *
 * 使用 DeepSeek Chat API（https://api.deepseek.com/chat/completions），
 * 需要在设置里填入 API Key。Key 存 SharedPreferences，不上传到任何第三方。
 */
object PetChatApi {

    private const val ENDPOINT = "https://api.deepseek.com/chat/completions"
    private const val MODEL = "deepseek-chat"
    private const val TIMEOUT_MS = 30_000

    /** 默认人设（对齐 dsh-pet config.jsonc 的 whisperPrompt） */
    const val DEFAULT_PERSONA =
        "你是主人桌面上的Q版蓝发小女仆，会时不时碎碎念一句。" +
            "说话要自然随意、短短一句（20字以内），温柔乖巧带点俏皮，" +
            "说人话不啰嗦，不要解释你自己，不要提你是AI。"

    /** 一条对话消息 */
    data class Message(val role: String, val content: String)

    /** 生成结果 */
    sealed class ChatResult {
        data class Success(val text: String) : ChatResult()
        data class Failure(val message: String) : ChatResult()
    }

    /**
     * 发起一次对话。
     *
     * @param apiKey      DeepSeek API Key
     * @param history     历史消息（最近 N 轮，role 为 user/assistant）
     * @param userInput   用户本次输入（碎碎念场景传 null，此时只让模型主动说一句）
     * @param persona     人设（system prompt 正文）
     */
    suspend fun chat(
        apiKey: String,
        history: List<Message>,
        userInput: String?,
        persona: String = DEFAULT_PERSONA,
        maxTokens: Int = 200,
    ): ChatResult = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) {
            return@withContext ChatResult.Failure("未设置 API Key，请到设置中填写")
        }
        try {
            // system prompt：人设 + 当前时间背景（让碎碎念更自然）
            val systemPrompt = buildString {
                append(persona.trim())
                append("\n\n你的名字是「小蓝」，现在是 ")
                append(formatNowForPrompt())
                append("。")
                if (userInput == null) append("请主动随口说一句话，不要问问题。")
            }

            val messages = JSONArray()
            messages.put(JSONObject().apply {
                put("role", "system")
                put("content", systemPrompt)
            })
            // 历史消息
            history.forEach { m ->
                messages.put(JSONObject().apply {
                    put("role", m.role)
                    put("content", m.content)
                })
            }
            // 本次输入
            if (userInput != null && userInput.isNotBlank()) {
                messages.put(JSONObject().apply {
                    put("role", "user")
                    put("content", userInput)
                })
            }

            val body = JSONObject().apply {
                put("model", MODEL)
                put("messages", messages)
                put("max_tokens", maxTokens)
                put("temperature", 1.3)   // 稍高温度，让碎碎念更活泼
                put("stream", false)
            }

            val conn = (URL(ENDPOINT).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Authorization", "Bearer $apiKey")
                setRequestProperty("Accept", "application/json")
            }

            try {
                OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use {
                    it.write(body.toString())
                }
                val code = conn.responseCode
                val text = if (code in 200..299) {
                    conn.inputStream.bufferedReader().use { r -> r.readText() }
                } else {
                    val err = try {
                        conn.errorStream?.bufferedReader()?.use { r -> r.readText() } ?: ""
                    } catch (_: Exception) { "" }
                    val hint = when (code) {
                        401 -> "API Key 无效或已过期"
                        402 -> "账户余额不足"
                        429 -> "请求太频繁，请稍后再试"
                        else -> "服务返回 $code"
                    }
                    return@withContext ChatResult.Failure(hint)
                }

                val content = JSONObject(text)
                    .optJSONArray("choices")
                    ?.optJSONObject(0)
                    ?.optJSONObject("message")
                    ?.optString("content", "")
                    ?.trim()

                if (content.isNullOrEmpty()) {
                    ChatResult.Failure("模型未返回内容")
                } else {
                    ChatResult.Success(content)
                }
            } finally {
                conn.disconnect()
            }
        } catch (e: java.net.SocketTimeoutException) {
            ChatResult.Failure("请求超时，请检查网络")
        } catch (e: Exception) {
            ChatResult.Failure("请求失败：${e.message ?: e.javaClass.simpleName}")
        }
    }

    /** 生成一句碎碎念（无用户输入） */
    suspend fun whisper(apiKey: String, persona: String = DEFAULT_PERSONA): ChatResult =
        chat(apiKey, emptyList(), null, persona)

    /** 当前时间背景（对齐 dsh-pet 的时间注入） */
    private fun formatNowForPrompt(): String {
        val now = java.util.Calendar.getInstance()
        val dow = arrayOf("周日", "周一", "周二", "周三", "周四", "周五", "周六")
        val hour = now.get(java.util.Calendar.HOUR_OF_DAY)
        val period = when (hour) {
            in 5..8 -> "清晨"
            in 9..11 -> "上午"
            in 12..13 -> "中午"
            in 14..17 -> "下午"
            in 18..19 -> "傍晚"
            in 20..22 -> "晚上"
            else -> "深夜"
        }
        return "${now.get(java.util.Calendar.YEAR)}年" +
            "${now.get(java.util.Calendar.MONTH) + 1}月" +
            "${now.get(java.util.Calendar.DAY_OF_MONTH)}日 " +
            "${dow[(now.get(java.util.Calendar.DAY_OF_WEEK) - 1).coerceIn(0, 6)]}" +
            " $period${hour}点"
    }
}
