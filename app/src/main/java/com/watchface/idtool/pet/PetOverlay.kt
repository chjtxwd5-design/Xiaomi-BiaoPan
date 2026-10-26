package com.watchface.idtool.pet

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 桌宠总控 —— 把状态机 / 渲染 / 菜单 / AI / 余额串起来。
 *
 * 用法：在 MainActivity 的最外层 Box 里调用 [PetOverlay]，它自己处理：
 *   · 加载动画清单与设置
 *   · 每帧驱动状态机（由 PetSprite 内部负责）
 *   · 自动碎碎念定时器
 *   · 长按 → 菜单 → 对话/余额/设置
 */
@Composable
fun PetOverlay(
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    if (!enabled) return
    val context = LocalContext.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()

    // 加载动画清单 + 设置（只跑一次）
    var ready by remember { mutableStateOf(PetAnimations.isLoaded) }
    LaunchedEffect(Unit) {
        if (!PetAnimations.isLoaded) {
            withContext(kotlinx.coroutines.Dispatchers.IO) {
                PetAnimations.load(context)
                PetStorage.loadSwitches(context)
            }
            ready = PetAnimations.isLoaded
        } else {
            PetStorage.loadSwitches(context)
        }
    }

    if (!ready) return

    BoxWithConstraints(modifier.fillMaxSize()) {
        val screenW = with(density) { maxWidth.toPx() }
        val screenH = with(density) { maxHeight.toPx() }

        val state = remember {
            PetState(
                initialSize = PetStorage.getSize(context),
                screenW = screenW,
                screenH = screenH,
            )
        }

        // 恢复上次位置
        LaunchedEffect(Unit) {
            PetStorage.getPosition(context)?.let { (sx, sy) ->
                // 位置合理性校验，越界则回默认
                if (sx in -200f..screenW && sy in -100f..screenH) {
                    state.setPosition(sx, sy)
                }
            }
        }

        var showMenu by remember { mutableStateOf(false) }
        var showChat by remember { mutableStateOf(false) }
        var showSettings by remember { mutableStateOf(false) }
        var apiKey by remember { mutableStateOf(PetStorage.getApiKey(context)) }

        // 自动碎碎念定时器（默认 300 秒，开关打开时生效）
        LaunchedEffect(Unit) {
            while (true) {
                delay(300_000)
                if (PetStorage.whisperEnabled && apiKey.isNotBlank()) {
                    val persona = PetStorage.getPersona(context)
                    when (val r = PetChatApi.whisper(apiKey, persona)) {
                        is PetChatApi.ChatResult.Success -> {
                            state.showBubble(r.text)
                            state.playNamed(PetAnimations.whisperAnims().firstOrNull() ?: PetAnimations.idleAnim())
                            delay(10_000)
                            state.clearBubble()
                        }
                        is PetChatApi.ChatResult.Failure -> Unit
                    }
                }
            }
        }

        // 渲染宠物
        PetSprite(
            state = state,
            modifier = Modifier.fillMaxSize(),
            onLongPress = { showMenu = true }
        )

        // 保存位置（松手/移动结束后）
        LaunchedEffect(state.x, state.y) {
            PetStorage.setPosition(context, state.x, state.y)
        }

        // ---- 菜单 ----
        if (showMenu) {
            PetMenu.MenuDialog(
                onDismiss = { showMenu = false },
                onChat = {
                    showMenu = false
                    apiKey = PetStorage.getApiKey(context)
                    showChat = true
                },
                onBalance = {
                    showMenu = false
                    if (apiKey.isNotBlank()) {
                        scope.launch {
                            when (val r = PetBalanceApi.query(apiKey)) {
                                is PetBalanceApi.Result.Success -> {
                                    val budget = PetStorage.getInitialBudget(context)
                                    val p = r.info.tightnessPercent(budget)
                                    state.playNamed(PetAnimations.balanceAnimFor(p))
                                    state.showBubble(
                                        "余额 ${r.info.currency} ${"%.2f".format(r.info.totalBalance)}"
                                    )
                                    delay(3000)
                                    state.clearBubble()
                                }
                                is PetBalanceApi.Result.Failure -> {
                                    state.showBubble(r.message)
                                    delay(3000)
                                    state.clearBubble()
                                }
                            }
                        }
                    } else {
                        showSettings = true
                    }
                },
                onResetPosition = {
                    showMenu = false
                    state.resetPosition()
                },
                onRandomAction = {
                    showMenu = false
                    state.playNamed(PetAnimations.randomAction())
                },
                onSettings = {
                    showMenu = false
                    apiKey = PetStorage.getApiKey(context)
                    showSettings = true
                },
            )
        }

        // ---- 对话窗 ----
        if (showChat) {
            PetMenu.ChatDialog(
                apiKey = apiKey,
                persona = PetStorage.getPersona(context),
                memory = PetStorage.recentMemory(context),
                onDismiss = { showChat = false },
                onSend = { text, onReply ->
                    scope.launch {
                        val history = PetStorage.recentMemory(context)
                        when (val r = PetChatApi.chat(
                            apiKey = PetStorage.getApiKey(context),
                            history = history,
                            userInput = text,
                            persona = PetStorage.getPersona(context),
                        )) {
                            is PetChatApi.ChatResult.Success -> onReply(r.text)
                            is PetChatApi.ChatResult.Failure -> onReply("错误:${r.message}")
                        }
                    }
                },
                onSaveMemory = { role, content ->
                    PetStorage.appendMemory(context, role, content)
                },
            )
        }

        // ---- 设置面板 ----
        if (showSettings) {
            PetMenu.SettingsDialog(
                apiKey = apiKey,
                persona = PetStorage.getPersona(context),
                chatOn = PetStorage.chatEnabled,
                whisperOn = PetStorage.whisperEnabled,
                balanceOn = PetStorage.balanceEnabled,
                petSize = PetStorage.getSize(context),
                onDismiss = { showSettings = false },
                onSave = { k, ps, c, w, b, sz ->
                    PetStorage.setApiKey(context, k)
                    PetStorage.setPersona(context, ps)
                    PetStorage.setChatEnabled(context, c)
                    PetStorage.setWhisperEnabled(context, w)
                    PetStorage.setBalanceEnabled(context, b)
                    PetStorage.setSize(context, sz)
                    apiKey = k
                    state.resize(sz)
                    showSettings = false
                },
            )
        }
    }
}
