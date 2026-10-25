package com.watchface.idtool.util

import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

/**
 * 统一的子进程执行 / 输出捕获工具。
 *
 * 修复此前「先 readBytes stdout、再读 stderr」的模式：当子进程向 stderr
 * 写满管道缓冲区（约 64KB）后会阻塞在 stderr 的 write 上，而调用方仍在等
 * stdout 的 EOF → 互等死锁。这里：
 *   1. stderr 由独立守护线程并发消费；
 *   2. stdout 由调用线程读取；
 *   3. waitFor 带超时，超时 destroyForcibly 并判定失败。
 */
object ProcessExec {

    class ExecResult(
        /** 标准输出原始字节 */
        val stdout: ByteArray,
        /** 标准错误文本（UTF-8） */
        val stderr: String,
        /** false = 超时被强制销毁 / 等待被中断 / 流读取异常，此时输出不可信 */
        val success: Boolean
    )

    /** 捕获进程输出：并发消费 stderr + 带超时 waitFor，杜绝管道死锁与进程悬挂 */
    fun capture(process: Process, timeoutSeconds: Long): ExecResult {
        val stderrBuf = ByteArrayOutputStream()
        val stderrDrainer = Thread {
            try {
                process.errorStream.use { it.copyTo(stderrBuf) }
            } catch (_: Exception) {
            }
        }
        stderrDrainer.isDaemon = true
        stderrDrainer.start()
        try {
            val stdout = process.inputStream.use { it.readBytes() }
            val finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
            if (!finished) {
                // 超时：强制销毁并判失败
                process.destroyForcibly()
                stderrDrainer.join(1000)
                return ExecResult(stdout, stderrBuf.toString("UTF-8"), false)
            }
            stderrDrainer.join(2000)
            return ExecResult(stdout, stderrBuf.toString("UTF-8"), true)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            try {
                process.destroyForcibly()
            } catch (_: Exception) {
            }
            return ExecResult(ByteArray(0), "", false)
        } catch (e: Exception) {
            try {
                process.destroyForcibly()
            } catch (_: Exception) {
            }
            return ExecResult(ByteArray(0), stderrBuf.toString("UTF-8"), false)
        } finally {
            try {
                process.destroy()
            } catch (_: Exception) {
            }
        }
    }

    /**
     * 单引号 shell 转义。仅用于不可避免要经过 shell 的场景（如 `su -c`），
     * 防止路径中的特殊字符（引号 / 分号 / 反引号 / $ 等）被 shell 解释（命令注入）。
     */
    fun shellQuote(s: String): String = "'" + s.replace("'", "'\\''") + "'"
}
