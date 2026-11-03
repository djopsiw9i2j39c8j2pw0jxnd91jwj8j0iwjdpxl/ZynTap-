package com.nuliz.ads

import android.content.Context

/**
 * Tập tên miền bị chặn, nạp từ các file .txt trong assets/blocklists (mỗi dòng một tên miền).
 * Khớp cả tên miền con: `a.b.ads.com` bị chặn nếu `ads.com` nằm trong danh sách.
 */
class Blocklist private constructor(private val domains: HashMap<String, Category>) {

    val size get() = domains.size

    fun match(host: String): Category? {
        var h = host
        while (true) {
            domains[h]?.let { return it }
            val dot = h.indexOf('.')
            if (dot < 0) return null
            h = h.substring(dot + 1)
        }
    }

    companion object {
        @Volatile
        private var cached: Blocklist? = null

        fun get(context: Context): Blocklist =
            cached ?: synchronized(this) { cached ?: load(context).also { cached = it } }

        private fun load(context: Context): Blocklist {
            val map = HashMap<String, Category>(300_000)
            // Duyệt theo thứ tự khai báo: danh sách sau ghi đè, nên "mã độc" ưu tiên hơn "quảng cáo".
            for (category in Category.entries) {
                context.assets.open("blocklists/${category.file}").bufferedReader().useLines { lines ->
                    lines.map { it.trim() }
                        .filter { it.isNotEmpty() && !it.startsWith("#") }
                        .forEach { map[it] = category }
                }
            }
            return Blocklist(map)
        }
    }
}
