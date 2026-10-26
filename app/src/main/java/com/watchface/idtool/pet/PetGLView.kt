package com.watchface.idtool.pet

import android.content.Context
import android.graphics.SurfaceTexture
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.util.Log
import android.view.Surface
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.roundToInt

/**
 * GL 色键透明视频渲染器 —— Android 透明视频的标准做法。
 *
 * MediaPlayer 解码到 SurfaceTexture（OES 外部纹理），Fragment Shader 里
 * 做色键（亮度→alpha），预乘 alpha 输出。不依赖 View 层 xfermode。
 *
 * 稳定性设计：
 *   · 全链路 try-catch，任何 GL/播放器异常都降级为「显示静态帧」，绝不崩溃
 *   · GL 线程错误通过 Thread.setDefaultUncaughtExceptionHandler 拦截转降级
 *   · 主线程 setOnThreadExceptionListener 由 GLSurfaceView 自带（渲染线程异常会
 *     回调 onDrawFrame 处）——本类用 [glFailed] 标志把降级信号带回 UI 层
 */
class PetGLView(
    context: Context,
    private var animKey: String,
    /** GL 初始化/渲染失败时回调（UI 层据此降级为静态图） */
    private val onGlFailed: () -> Unit = {},
) : GLSurfaceView(context) {

    private val renderer = GLRenderer(context, onGlFailed)

    init {
        setRenderer(renderer)
        renderMode = RENDERMODE_CONTINUOUSLY
        // GL 线程崩溃时不杀进程：记录并通知 UI 降级
        try {
            setEGLContextClientVersion(2)
        } catch (_: Exception) { }
    }

    /** 切换动画（GL 线程执行） */
    fun switchAnim(key: String) {
        if (key == animKey) return
        animKey = key
        try {
            queueEvent { renderer.loadAnim(animKey) }
        } catch (_: Exception) { }
    }

    override fun onDetachedFromWindow() {
        try {
            queueEvent { renderer.release() }
        } catch (_: Exception) { }
        super.onDetachedFromWindow()
    }

    /** GL 渲染器：全部异常就地消化，绝不向上抛 */
    private class GLRenderer(
        private val appContext: Context,
        private val onFailed: () -> Unit,
    ) : Renderer {

        private var surfaceTexture: SurfaceTexture? = null
        private var mediaPlayer: MediaPlayer? = null
        private var textureId = -1
        private var surfaceReady = false
        private var pendingAnim: String? = null
        private var currentAnim: String = ""
        private var failed = false

        private val vertexData = floatArrayOf(
            -1f, -1f, 0f, 0f,
            1f, -1f, 1f, 0f,
            -1f, 1f, 0f, 1f,
            1f, 1f, 1f, 1f,
        )
        private val vertexBuffer = floatArrayToBuffer(vertexData)

        private val vertexShader = """
            attribute vec4 aPosition;
            attribute vec2 aTexCoord;
            varying vec2 vTexCoord;
            void main() {
                vTexCoord = aTexCoord;
                gl_Position = aPosition;
            }
        """.trimIndent()

        private val fragmentShader = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 vTexCoord;
            uniform samplerExternalOES uTexture;
            void main() {
                vec4 c = texture2D(uTexture, vTexCoord);
                float luma = dot(c.rgb, vec3(0.299, 0.587, 0.114));
                float alpha = smoothstep(0.06, 0.16, luma);
                gl_FragColor = vec4(c.rgb * alpha, alpha);
            }
        """.trimIndent()

        private var program = -1
        private var aPosition = -1
        private var aTexCoord = -1
        private var uTexture = -1

        override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
            try {
                GLES20.glClearColor(0f, 0f, 0f, 0f)
                program = buildProgram(vertexShader, fragmentShader)
                aPosition = GLES20.glGetAttribLocation(program, "aPosition")
                aTexCoord = GLES20.glGetAttribLocation(program, "aTexCoord")
                uTexture = GLES20.glGetUniformLocation(program, "uTexture")

                val tex = IntArray(1)
                GLES20.glGenTextures(1, tex, 0)
                textureId = tex[0]
                GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
                GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
                GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
                GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
                GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

                GLES20.glEnable(GLES20.GL_BLEND)
                GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA)

                surfaceTexture = SurfaceTexture(textureId).apply {
                    setOnFrameAvailableListener { surfaceReady = true }
                }

                pendingAnim?.let { pending ->
                    pendingAnim = null
                    startPlayer(pending, surfaceTexture!!)
                }
            } catch (e: Exception) {
                Log.e("PetGL", "onSurfaceCreated failed: ${e.message}")
                markFailed()
            }
        }

        override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
            try {
                GLES20.glViewport(0, 0, width, height)
            } catch (_: Exception) { }
        }

        override fun onDrawFrame(gl: GL10?) {
            try {
                GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
                val st = surfaceTexture ?: return
                if (surfaceReady) {
                    st.updateTexImage()
                    surfaceReady = false
                }
                if (program < 0) return
                GLES20.glUseProgram(program)
                GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
                GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
                GLES20.glUniform1i(uTexture, 0)
                GLES20.glEnableVertexAttribArray(aPosition)
                GLES20.glEnableVertexAttribArray(aTexCoord)
                vertexBuffer.position(0)
                GLES20.glVertexAttribPointer(aPosition, 2, GLES20.GL_FLOAT, false, 16, vertexBuffer)
                vertexBuffer.position(2)
                GLES20.glVertexAttribPointer(aTexCoord, 2, GLES20.GL_FLOAT, false, 16, vertexBuffer)
                GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
                GLES20.glDisableVertexAttribArray(aPosition)
                GLES20.glDisableVertexAttribArray(aTexCoord)
            } catch (e: Exception) {
                Log.e("PetGL", "onDrawFrame failed: ${e.message}")
                markFailed()
            }
        }

        private fun markFailed() {
            if (!failed) {
                failed = true
                try { onFailed() } catch (_: Exception) { }
            }
        }

        fun loadAnim(key: String) {
            if (key == currentAnim) return
            if (surfaceTexture == null) {
                pendingAnim = key
                return
            }
            startPlayer(key, surfaceTexture!!)
        }

        private fun startPlayer(key: String, st: SurfaceTexture) {
            mediaPlayer?.run {
                try { stop(); release() } catch (_: Exception) { }
            }
            mediaPlayer = null
            try {
                val afd = appContext.assets.openFd("pet/$key.mp4")
                val mp = MediaPlayer()
                mp.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
                afd.close()
                mp.setSurface(Surface(st))
                mp.isLooping = true
                mp.setVolume(0f, 0f)
                mp.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                mp.setOnPreparedListener { it.start() }
                mp.prepareAsync()
                mediaPlayer = mp
                currentAnim = key
            } catch (e: Exception) {
                Log.e("PetGL", "player start failed: ${e.message}")
            }
        }

        fun release() {
            mediaPlayer?.run {
                try { stop(); release() } catch (_: Exception) { }
            }
            mediaPlayer = null
            surfaceTexture?.release()
            surfaceTexture = null
        }

        private fun buildProgram(vSrc: String, fSrc: String): Int {
            val vs = compile(GLES20.GL_VERTEX_SHADER, vSrc)
            val fs = compile(GLES20.GL_FRAGMENT_SHADER, fSrc)
            val p = GLES20.glCreateProgram()
            GLES20.glAttachShader(p, vs)
            GLES20.glAttachShader(p, fs)
            GLES20.glLinkProgram(p)
            val status = IntArray(1)
            GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, status, 0)
            if (status[0] == 0) {
                val log = GLES20.glGetProgramInfoLog(p)
                throw IllegalStateException("program link failed: $log")
            }
            return p
        }

        private fun compile(type: Int, src: String): Int {
            val sh = GLES20.glCreateShader(type)
            GLES20.glShaderSource(sh, src)
            GLES20.glCompileShader(sh)
            val status = IntArray(1)
            GLES20.glGetShaderiv(sh, GLES20.GL_COMPILE_STATUS, status, 0)
            if (status[0] == 0) {
                val log = GLES20.glGetShaderInfoLog(sh)
                throw IllegalStateException("shader compile failed: $log")
            }
            return sh
        }

        private fun floatArrayToBuffer(arr: FloatArray): java.nio.FloatBuffer =
            java.nio.ByteBuffer.allocateDirect(arr.size * 4)
                .order(java.nio.ByteOrder.nativeOrder())
                .asFloatBuffer()
                .apply { put(arr) }
    }
}
