package com.watchface.idtool

import android.content.pm.PackageManager
import com.watchface.idtool.util.ProcessExec
import rikka.shizuku.Shizuku
import java.lang.reflect.Method
import java.util.concurrent.TimeUnit

object ShizukuExecutor {

    /** 单条命令最长等待时间（秒），防止异常命令导致进程无限挂起 */
    private const val PROCESS_TIMEOUT_SECONDS = 20L

    /** Shizuku 服务是否正在运行 */
    fun isShizukuRunning(): Boolean {
        return try {
            Shizuku.pingBinder()
        } catch (e: Exception) {
            false
        }
    }

    /** 是否已获得 Shizuku 授权 */
    fun hasPermission(): Boolean {
        return try {
            if (!isShizukuRunning()) return false
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (e: Exception) {
            false
        }
    }

    /** 通过反射调用 Shizuku.newProcess()，返回 Process 对象 */
    private fun newProcess(cmd: Array<String>, env: Array<String>?, dir: String?): Process? {
        return try {
            val method: Method = Shizuku::class.java.getDeclaredMethod(
                "newProcess",
                Array<String>::class.java,
                Array<String>::class.java,
                String::class.java
            )
            method.isAccessible = true
            method.invoke(null, cmd, env, dir) as? Process
        } catch (e: Exception) {
            null
        }
    }

    /** 统一捕获输出：stderr 由独立线程并发消费 + 带超时 waitFor，防管道死锁 */
    private fun capture(process: Process): ProcessExec.ExecResult =
        ProcessExec.capture(process, PROCESS_TIMEOUT_SECONDS)

    /**
     * 通过 Shizuku 执行命令，返回输出文本。
     * 确保进程和流正确关闭，不留残留。
     */
    fun execCommand(cmd: String): String {
        val process = newProcess(arrayOf("sh", "-c", cmd), null, null) ?: return ""
        val result = try {
            capture(process)
        } finally {
            process.destroy()
        }
        return if (result.success) String(result.stdout) else ""
    }

    /** 通过 Shizuku 读取文件全部内容（参数数组直传，不经 shell -c，防命令注入） */
    fun readFile(path: String): ByteArray {
        // 优先参数数组直传（无 shell 解析，天然免疫路径注入）
        newProcess(arrayOf("cat", path), null, null)?.let { process ->
            val result = try {
                capture(process)
            } finally {
                process.destroy()
            }
            if (result.success) return result.stdout
        }
        // 兜底：经 sh -c 执行，路径做单引号转义防注入
        val process = newProcess(
            arrayOf("sh", "-c", "cat ${ProcessExec.shellQuote(path)}"), null, null
        ) ?: return ByteArray(0)
        val result = try {
            capture(process)
        } finally {
            process.destroy()
        }
        return if (result.success) result.stdout else ByteArray(0)
    }

    /** 通过 Shizuku 只读取文件头部指定字节数，大幅减少 IO 和内存占用 */
    fun readPartialFile(path: String, byteCount: Int): ByteArray {
        // 优先参数数组直传 dd（无 shell 解析）
        newProcess(arrayOf("dd", "if=$path", "bs=$byteCount", "count=1"), null, null)
            ?.let { process ->
                val result = try {
                    capture(process)
                } finally {
                    process.destroy()
                }
                if (result.success) return result.stdout
            }
        // 兜底：经 sh -c，路径做单引号转义防注入
        val process = newProcess(
            arrayOf("sh", "-c", "dd if=${ProcessExec.shellQuote(path)} bs=$byteCount count=1 2>/dev/null"),
            null, null
        ) ?: return ByteArray(0)
        val result = try {
            capture(process)
        } finally {
            process.destroy()
        }
        return if (result.success) result.stdout else ByteArray(0)
    }

    /** 通过 Shizuku 获取文件信息（大小和修改时间） */
    fun getFileInfo(path: String): Pair<Long, Long> {
        val output = execCommand("stat -c '%s %Y' ${ProcessExec.shellQuote(path)}").trim()
        val parts = output.split(" ")
        if (parts.size >= 2) {
            return Pair(
                parts[0].toLongOrNull() ?: 0L,
                parts[1].toLongOrNull() ?: 0L
            )
        }
        return Pair(0L, 0L)
    }
}
