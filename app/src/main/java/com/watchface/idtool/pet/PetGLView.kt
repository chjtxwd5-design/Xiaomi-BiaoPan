package com.watchface.idtool.pet

import android.content.Context
import android.graphics.SurfaceTexture
import android.media.MediaPlayer
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.view.Surface
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.roundToInt

/**
 * GL 色键透明视频渲染器 —— Android 透明视频的标准做法。
 *
 * 原理：MediaPlayer 解码到 SurfaceTexture（外部 OES 纹理），
 * Fragment Shader 里做色键：亮度接近纯黑的像素 alpha→0（透明），
 * 其余像素预乘 alpha 后与背景混合。不依赖 xfermode，全 GPU，兼容所有机型。
 *
 * 与网页版 dsh-pet 的 CSS 滤色等价：黑底完全消失，白色描边保留。
 */
class PetGLView(
    context: Context,
    /** 动画资源名（assets/pet/<anim>.mp4） */
    private var animKey: String,
) : GLSurfaceView(context) {

    private val renderer = GLRenderer(context)

    init {
        setRenderer(renderer)
        renderMode = RENDERMODE_CONTINUOUSLY
    }

    /** 切换动画（释放旧播放器，加载新的） */
    fun switchAnim(key: String) {
        if (key == animKey) return
        animKey = key
        queueEvent { renderer.loadAnim(animKey) }
    }

    override fun onDetachedFromWindow() {
        queueEvent { renderer.release() }
        super.onDetachedFromWindow()
    }

    /** GL 渲染器：纹理 + 着色器 + MediaPlayer 生命周期都在 GL 线程 */
    private class GLRenderer(private val appContext: Context) : Renderer {

        private var surfaceTexture: SurfaceTexture? = null
        private var mediaPlayer: MediaPlayer? = null
        private var textureId = -1
        private var surfaceReady = false
        private var pendingAnim: String? = null
        private var currentAnim: String = ""

        // 顶点：两个三角形铺满视口（-1..1）
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

        /** 色键片元着色器：亮度→alpha，预乘输出 */
        private val fragmentShader = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 vTexCoord;
            uniform samplerExternalOES uTexture;
            void main() {
                vec4 c = texture2D(uTexture, vTexCoord);
                float luma = dot(c.rgb, vec3(0.299, 0.587, 0.114));
                // 黑底→透明：luma 低于 0.06 全透明，高于 0.16 全不透明，中间平滑过渡
                float alpha = smoothstep(0.06, 0.16, luma);
                // 预乘 alpha（供 GL_ONE, GL_ONE_MINUS_SRC_ALPHA 混合）
                gl_FragColor = vec4(c.rgb * alpha, alpha);
            }
        """.trimIndent()

        private var program = -1
        private var aPosition = -1
        private var aTexCoord = -1
        private var uTexture = -1

        override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
            GLES20.glClearColor(0f, 0f, 0f, 0f)
            program = buildProgram(vertexShader, fragmentShader)
            aPosition = GLES20.glGetAttribLocation(program, "aPosition")
            aTexCoord = GLES20.glGetAttribLocation(program, "aTexCoord")
            uTexture = GLES20.glGetUniformLocation(program, "uTexture")

            val tex = IntArray(1)
            GLES20.glGenTextures(1, tex, 0)
            textureId = tex[0]
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
            GLES20.glTexParameteri(
                GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR
            )
            GLES20.glTexParameteri(
                GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR
            )
            GLES20.glTexParameteri(
                GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE
            )
            GLES20.glTexParameteri(
                GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE
            )

            // 混合：预乘 alpha 的标准配方
            GLES20.glEnable(GLES20.GL_BLEND)
            GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA)

            surfaceTexture = SurfaceTexture(textureId).apply {
                setOnFrameAvailableListener { surfaceReady = true }
            }
            // 通知 View 层：Surface 可用了，开始加载 pending 动画
            pendingAnim?.let { pending ->
                pendingAnim = null
                startPlayer(pending, surfaceTexture!!)
            }
        }

        override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
            GLES20.glViewport(0, 0, width, height)
        }

        override fun onDrawFrame(gl: GL10?) {
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            val st = surfaceTexture ?: return
            if (surfaceReady) {
                st.updateTexImage()
                surfaceReady = false
            }
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
        }

        /** GL 线程内：切换动画 */
        fun loadAnim(key: String) {
            if (key == currentAnim) return
            if (surfaceTexture == null) {
                pendingAnim = key   // surface 还没就绪，等 onSurfaceCreated 再放
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
                    android.media.AudioAttributes.Builder()
                        .setUsage(android.media.AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                mp.setOnPreparedListener { it.start() }
                mp.prepareAsync()
                mediaPlayer = mp
                currentAnim = key
            } catch (_: Exception) {
                // 动画缺失/解码失败：保持空画面，状态机稍后会切别的动画
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
