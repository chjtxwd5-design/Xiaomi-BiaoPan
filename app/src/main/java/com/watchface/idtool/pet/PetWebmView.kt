package com.watchface.idtool.pet

import android.content.Context
import android.graphics.PixelFormat
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.util.Log
import android.view.SurfaceHolder
import android.view.SurfaceView
import kotlin.math.roundToInt

/**
 * 桌宠动画播放器 —— 直接播放源 WebM（VP9 + ALPHA_MODE=1）。
 *
 * 关键认知：dsh-pet 的源素材是**真正带 alpha 通道的 VP9 WebM**（ALPHA_MODE=1），
 * 不是黑底视频！之前转成 MP4 时 `-pix_fmt yuv420p` 把 alpha 丢了，才需要各种
 * 色键/滤色 hack（全都不靠谱）。
 *
 * Android 的 VP9 解码器（MediaCodec）**原生支持 VP9 alpha**，MediaPlayer 播放
 * WebM 时系统会自动合成 alpha —— 不需要 GL、不需要 xfermode、不需要色键。
 *
 * 用 SurfaceView（而非 TextureView）：SurfaceView 由系统合成层直接处理 alpha，
 * 是最可靠的透明视频路径；且比 GLSurfaceView 简单，不会因 EGL 初始化崩溃。
 */
class PetWebmView(
    context: Context,
    private var animKey: String,
) : SurfaceView(context), SurfaceHolder.Callback {

    private var mediaPlayer: MediaPlayer? = null

    init {
        // 透明背景：SurfaceView 需要明确不要不透明格式
        holder.setFormat(PixelFormat.TRANSLUCENT)
        holder.addCallback(this)
        setZOrderOnTop(false)
    }

    /** 切换到指定动画 */
    fun playAnim(key: String) {
        if (key == animKey && mediaPlayer != null) return
        animKey = key
        startPlayer(key)
    }

    private fun startPlayer(key: String) {
        releasePlayer()
        try {
            val afd = context.assets.openFd("pet_webm/$key.webm")
            val mp = MediaPlayer()
            mp.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
            afd.close()
            mp.setDisplay(holder)
            mp.isLooping = true
            mp.setVolume(0f, 0f)
            // 声明为系统提示音 + 静音：不打断后台音乐
            mp.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            mp.setOnPreparedListener {
                try {
                    it.start()
                    Log.i("PetWebm", "playing $key")
                } catch (e: Exception) {
                    Log.e("PetWebm", "start failed: ${e.message}")
                }
            }
            mp.setOnErrorListener { _, what, extra ->
                Log.e("PetWebm", "error what=$what extra=$extra key=$key")
                true
            }
            mp.prepareAsync()
            mediaPlayer = mp
        } catch (e: Exception) {
            Log.e("PetWebm", "load failed key=$key err=${e.message}")
        }
    }

    private fun releasePlayer() {
        mediaPlayer?.run {
            try { stop() } catch (_: Exception) { }
            try { release() } catch (_: Exception) { }
        }
        mediaPlayer = null
    }

    // ---- SurfaceHolder.Callback ----
    override fun surfaceCreated(h: SurfaceHolder) {
        // Surface 就绪：如果已有动画请求，立刻播放
        if (animKey.isNotEmpty()) startPlayer(animKey)
    }

    override fun surfaceChanged(h: SurfaceHolder, format: Int, width: Int, height: Int) = Unit

    override fun surfaceDestroyed(h: SurfaceHolder) {
        releasePlayer()
    }

    override fun onDetachedFromWindow() {
        releasePlayer()
        super.onDetachedFromWindow()
    }
}
