package com.watchface.idtool.util

/**
 * 通用文本工具：合并此前 SagAuthManager / ApkDownloader / HitokotoApi 三份
 * HTML 实体反转义，以及 SagAuthManager / CloudConfigManager 两份版本号比较实现。
 */
object TextUtil {

    /** 反转义常见 HTML 实体（&amp; &lt; &gt; &quot; &#39; &apos; &nbsp;） */
    fun unescapeHtmlEntities(s: String): String {
        if (s.isEmpty()) return s
        return s
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&apos;", "'")
            .replace("&nbsp;", " ")
    }

    /** 版本号比较：a > b 返回 1，a < b 返回 -1，相等返回 0（忽略非数字字符） */
    fun compareVersion(a: String, b: String): Int {
        val pa = a.split(".", "-").mapNotNull { it.filter { c -> c.isDigit() }.toIntOrNull() }
        val pb = b.split(".", "-").mapNotNull { it.filter { c -> c.isDigit() }.toIntOrNull() }
        val n = maxOf(pa.size, pb.size)
        for (i in 0 until n) {
            val x = pa.getOrElse(i) { 0 }
            val y = pb.getOrElse(i) { 0 }
            if (x != y) return x.compareTo(y)
        }
        return 0
    }
}
