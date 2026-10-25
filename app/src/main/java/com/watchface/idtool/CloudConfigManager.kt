package com.watchface.idtool

import android.content.Context
import android.content.pm.PackageManager
import com.watchface.idtool.util.TextUtil

/**
 * 公告 / 版本检查 —— 统一走微验 T4Verify（getNotice / checkUpdate）
 */
object CloudConfigManager {

    data class CloudConfig(
        val enabled: Boolean,
        val announcement: String,
        val latestVersion: String,
        val updateUrl: String,
        val hasUpdate: Boolean = false,
        val updateLog: String = ""
    )

    fun getCurrentVersion(context: Context): String {
        return try {
            val pi = context.packageManager.getPackageInfo(context.packageName, 0)
            pi.versionName ?: "1.0.0"
        } catch (_: Exception) {
            "1.0.0"
        }
    }

    suspend fun fetchConfig(context: Context): CloudConfig? {
        return try {
            // 确保微验 SDK 已初始化
            SagAuthManager.init(context.applicationContext)
            val local = getCurrentVersion(context)
            val nv = SagAuthManager.fetchNoticeAndVersion(local)
            val hasUpdate = TextUtil.compareVersion(nv.latestVersion, local) > 0
            CloudConfig(
                enabled = true,
                announcement = nv.notice,
                latestVersion = nv.latestVersion.ifBlank { local },
                updateUrl = nv.updateUrl,
                hasUpdate = hasUpdate,
                updateLog = nv.updateLog
            )
        } catch (e: Exception) {
            null
        }
    }
}
