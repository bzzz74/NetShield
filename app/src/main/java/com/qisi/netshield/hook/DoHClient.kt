package com.qisi.netshield.hook

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

/**
 * DNS over HTTPS（JSON 模式）解析器，只在 Hook 回调内被调用。
 * 配合 ThreadLocal 防止“自身发请求 → 触发 InetAddress 钩子 → 再发请求”的递归。
 * 支持内置三提供商 + 自定义 HTTPS JSON 接口。
 */
object DoHClient {

    private const val TIMEOUT_MS = 5000
    private const val CACHE_TTL_MS = 120 * 1000L

    // host(lower) -> (expireAt, ips)
    private val cache = ConcurrentHashMap<String, Pair<Long, List<InetAddress>>>()
    private val inFlight = ThreadLocal.withInitial { false }

    /** 返回解析结果；null 表示交给系统原逻辑（回退到普通 DNS） */
    fun resolve(host: String, provider: String, customUrl: String): List<InetAddress>? {
        if (host.isEmpty() || isIpLiteral(host)) return null
        if (inFlight.get()) return null

        val key = host.lowercase()
        val hit = cache[key]
        if (hit != null && System.currentTimeMillis() < hit.first) return hit.second

        return try {
            inFlight.set(true)
            val ips = query(host, "A", provider, customUrl) + query(host, "AAAA", provider, customUrl)
            if (ips.isEmpty()) {
                null
            } else {
                cache[key] = (System.currentTimeMillis() + CACHE_TTL_MS) to ips.distinct()
                ips.distinct()
            }
        } catch (t: Throwable) {
            null
        } finally {
            inFlight.set(false)
        }
    }

    private fun endpoint(provider: String, custom: String, host: String, type: String): String {
        // 自定义服务器：要求 https 的 JSON 风格 DoH 接口（RFC8484 json 扩展或兼容实现）
        if (provider == "custom") {
            val base = custom.trim().trimEnd('/')
            if (base.startsWith("https://")) {
                val sep = if (base.contains('?')) '&' else '?'
                return "$base${sep}name=$host&type=$type"
            }
            // 无效的自定义地址 → 回落 Cloudflare
        }
        return when (provider) {
            "google" -> "https://dns.google/resolve?name=$host&type=$type"
            "ali" -> "https://dns.alidns.com/resolve?name=$host&type=$type"
            else -> "https://cloudflare-dns.com/dns-query?name=$host&type=$type"
        }
    }

    private fun query(host: String, type: String, provider: String, custom: String): List<InetAddress> {
        val conn = (URL(endpoint(provider, custom, host, type)).openConnection() as HttpURLConnection).apply {
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            setRequestProperty("accept", "application/dns-json")
            requestMethod = "GET"
        }
        val body = conn.inputStream.use { it.readBytes().decodeToString() }
        conn.disconnect()
        val answers = JSONObject(body).optJSONArray("Answer") ?: return emptyList()
        val out = ArrayList<InetAddress>()
        for (i in 0 until answers.length()) {
            val a = answers.getJSONObject(i)
            val t = a.optInt("type", 0)
            val data = a.optString("data")
            if (data.isEmpty()) continue
            if (t == 1 || t == 28) {
                try { out.add(InetAddress.getByName(data)) } catch (e: Exception) { /* 忽略非法记录 */ }
            }
        }
        return out
    }

    /** 判断是否是 IP 字面量（这类地址不需要 DoH，直接放行原逻辑） */
    private fun isIpLiteral(host: String): Boolean {
        // 形如 1.2.3.4
        if (host.matches(Regex("(\\d{1,3}\\.){3}\\d{1,3}"))) return true
        // 含 ':' —— IPv6 字面量或端口形式
        if (host.contains(':')) return true
        return false
    }
}
