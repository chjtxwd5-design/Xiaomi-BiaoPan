package com.watchface.idtool.pet

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.launch

/**
 * 桌宠交互面板 —— 右键菜单 / 对话窗 / 设置。
 *
 * 菜单项（对齐 dsh-pet 右键菜单）：
 *   · 对话       打开聊天窗
 *   · 查看余额   立刻查询余额并播放对应档位动画
 *   · 回到初始位置
 *   · 换个动作   随机播一个动作
 *   · 设置       打开设置面板
 */
object PetMenu {

    private val MENU_BG = Color(0xF2FFFFFF)
    private val TEXT_MAIN = Color(0xFF1F2A44)
    private val TEXT_SUB = Color(0xFF6B7A9B)
    private val ACCENT = Color(0xFF4A6FA5)

    /** 长按弹出的动作菜单 */
    @Composable
    fun MenuDialog(
        onDismiss: () -> Unit,
        onChat: () -> Unit,
        onBalance: () -> Unit,
        onResetPosition: () -> Unit,
        onRandomAction: () -> Unit,
        onSettings: () -> Unit,
    ) {
        Dialog(onDismissRequest = onDismiss) {
            Column(
                modifier = Modifier
                    .width(200.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(Color.White)
                    .padding(vertical = 8.dp)
            ) {
                MenuItem("💬  对话", onChat)
                MenuItem("💰  查看余额", onBalance)
                MenuItem("🎲  换个动作", onRandomAction)
                MenuItem("🏠  回到初始位置", onResetPosition)
                MenuItem("⚙️  设置", onSettings)
            }
        }
    }

    @Composable
    private fun MenuItem(text: String, onClick: () -> Unit) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(horizontal = 18.dp, vertical = 13.dp)
        ) {
            Text(text = text, fontSize = 14.sp, color = TEXT_MAIN)
        }
    }

    /** 对话窗口 */
    @Composable
    fun ChatDialog(
        apiKey: String,
        persona: String,
        memory: List<PetChatApi.Message>,
        onDismiss: () -> Unit,
        onSend: (String, (String) -> Unit) -> Unit,
        onSaveMemory: (String, String) -> Unit,
    ) {
        var input by remember { mutableStateOf("") }
        var messages = remember { mutableStateOf(memory.toMutableList()) }
        var sending by remember { mutableStateOf(false) }
        var errorText by remember { mutableStateOf<String?>(null) }
        val scope = rememberCoroutineScope()

        Dialog(onDismissRequest = onDismiss) {
            Column(
                modifier = Modifier
                    .width(320.dp)
                    .heightIn(max = 480.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(Color.White)
                    .padding(16.dp)
            ) {
                Text("和小蓝聊天", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = TEXT_MAIN)
                Spacer(Modifier.height(10.dp))

                // 消息列表
                Column(
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .heightIn(max = 300.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    messages.value.forEach { m ->
                        val isUser = m.role == "user"
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(if (isUser) ACCENT.copy(alpha = 0.12f) else Color(0xFFF3F6FC))
                                .padding(10.dp),
                            contentAlignment = if (isUser) Alignment.CenterEnd else Alignment.CenterStart
                        ) {
                            Text(m.content, fontSize = 13.sp, color = TEXT_MAIN)
                        }
                    }
                    if (sending) {
                        Text("小蓝正在思考…", fontSize = 12.sp, color = TEXT_SUB)
                    }
                    errorText?.let {
                        Text(it, fontSize = 12.sp, color = Color(0xFFE0554D))
                    }
                }

                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    BasicTextField(
                        value = input,
                        onValueChange = { input = it },
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(10.dp))
                            .background(Color(0xFFF3F6FC))
                            .padding(10.dp),
                        textStyle = androidx.compose.ui.text.TextStyle(
                            fontSize = 13.sp, color = TEXT_MAIN
                        ),
                        singleLine = false,
                        maxLines = 3,
                    )
                    Spacer(Modifier.width(8.dp))
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (sending) ACCENT.copy(alpha = 0.5f) else ACCENT)
                            .clickable(enabled = !sending && input.isNotBlank()) {
                                val text = input.trim()
                                input = ""
                                sending = true
                                errorText = null
                                messages.value.add(PetChatApi.Message("user", text))
                                onSaveMemory("user", text)
                                onSend(text) { reply ->
                                    sending = false
                                    if (reply.startsWith("错误:")) {
                                        errorText = reply.removePrefix("错误:")
                                    } else {
                                        messages.value.add(PetChatApi.Message("assistant", reply))
                                        onSaveMemory("assistant", reply)
                                    }
                                }
                            }
                            .padding(horizontal = 14.dp, vertical = 10.dp)
                    ) {
                        Text("发送", fontSize = 13.sp, color = Color.White)
                    }
                }

                if (apiKey.isBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text("提示：请先在设置里填写 DeepSeek API Key", fontSize = 11.sp, color = Color(0xFFE0554D))
                }
            }
        }
    }

    /** 设置面板 */
    @Composable
    fun SettingsDialog(
        apiKey: String,
        persona: String,
        chatOn: Boolean,
        whisperOn: Boolean,
        balanceOn: Boolean,
        petSize: Float,
        onDismiss: () -> Unit,
        onSave: (
            apiKey: String, persona: String,
            chatOn: Boolean, whisperOn: Boolean, balanceOn: Boolean,
            size: Float
        ) -> Unit,
    ) {
        var key by remember { mutableStateOf(apiKey) }
        var ps by remember { mutableStateOf(persona) }
        var c by remember { mutableStateOf(chatOn) }
        var w by remember { mutableStateOf(whisperOn) }
        var b by remember { mutableStateOf(balanceOn) }
        var sz by remember { mutableStateOf(petSize) }

        Dialog(onDismissRequest = onDismiss) {
            Column(
                modifier = Modifier
                    .width(320.dp)
                    .heightIn(max = 520.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(Color.White)
                    .padding(16.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Text("桌宠设置", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = TEXT_MAIN)
                Spacer(Modifier.height(12.dp))

                FieldLabel("DeepSeek API Key")
                BasicTextField(
                    value = key,
                    onValueChange = { key = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color(0xFFF3F6FC))
                        .padding(10.dp),
                    textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.sp, color = TEXT_MAIN),
                    singleLine = true,
                )
                Spacer(Modifier.height(4.dp))
                Text("用于 AI 闲聊与余额查询，仅保存在本机", fontSize = 10.sp, color = TEXT_SUB)

                Spacer(Modifier.height(12.dp))
                FieldLabel("自定义人设")
                BasicTextField(
                    value = ps,
                    onValueChange = { ps = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 60.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color(0xFFF3F6FC))
                        .padding(10.dp),
                    textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.sp, color = TEXT_MAIN),
                )

                Spacer(Modifier.height(12.dp))
                ToggleRow("AI 对话", c) { c = it }
                ToggleRow("自动碎碎念", w) { w = it }
                ToggleRow("余额查询", b) { b = it }

                Spacer(Modifier.height(12.dp))
                FieldLabel("宠物大小：${sz.toInt()} dp")
                androidx.compose.material3.Slider(
                    value = sz,
                    onValueChange = { sz = it },
                    valueRange = 100f..320f,
                )

                Spacer(Modifier.height(14.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .background(ACCENT)
                            .clickable { onSave(key, ps, c, w, b, sz) }
                            .padding(horizontal = 18.dp, vertical = 10.dp)
                    ) {
                        Text("保存", fontSize = 13.sp, color = Color.White)
                    }
                }
            }
        }
    }

    @Composable
    private fun FieldLabel(text: String) {
        Text(text, fontSize = 11.sp, fontWeight = FontWeight.Medium, color = TEXT_SUB)
        Spacer(Modifier.height(5.dp))
    }

    @Composable
    private fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onChange(!checked) }
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(label, fontSize = 13.sp, color = TEXT_MAIN, modifier = Modifier.weight(1f))
            androidx.compose.material3.Switch(checked = checked, onCheckedChange = onChange)
        }
    }
}
