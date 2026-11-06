package com.qisi.netshield.hook

import com.qisi.netshield.common.Config
import org.json.JSONObject
import java.io.File

/**
 * 在被 Hook 的应用进程内读取模块配置（现代 API 版）：
 * 1) 主通道：XposedModule.getRemotePreferences —— LSPosed 框架数据库中的远程配置，
 *    由模块设置界面通过 XposedService 写入，两边天然同源；
 * 2) 备用通道：/data/local/tmp/netshield/config.json（su 同步的 JSON 副本）。
 * 带 2 秒缓存，避免高频 Hook 每次都做 IO。
 */
object ModuleConfig {

    private const val CACHE_MS = 2000L

    /** 由 HookEntry 在模块加载时注入，用于访问 RemotePreferences */
    @Volatile
    var xposed: io.github.libxposed.api.XposedInterface? = null

    @Volatile
    private var cache: Pair<Long, NetConfig>? = null

    fun load(): NetConfig {
        val now = System.currentTimeMillis()
        cache?.let { if (now - it.first < CACHE_MS) return it.second }
        synchronized(this) {
            cache?.let { if (now - it.first < CACHE_MS) return it.second }
            val cfg = readConfig()
            cache = now to cfg
            return cfg
        }
    }

    private fun readConfig(): NetConfig {
        readFromRemotePrefs()?.let { return it }
        readFromShadowFile()?.let { return it }
        return NetConfig(
            enabled = false, alwaysOn = false, forceHttps = true, blockPlainSocket = true,
            useDoh = true, dohProvider = "cloudflare", debugLog = true, dohCustom = ""
        )
    }

    private fun readFromRemotePrefs(): NetConfig? = try {
        val xp = xposed ?: return null
        val prefs = xp.getRemotePreferences(Config.PREFS)
        if (prefs.all.isNotEmpty()) fromMap(prefs.all) else null
    } catch (t: Throwable) {
        null
    }

    private fun readFromShadowFile(): NetConfig? = try {
        val f = File(Config.SHADOW_FILE)
        if (f.canRead()) parseJson(JSONObject(f.readText())) else null
    } catch (t: Throwable) {
        null
    }

    /** 键值对（RemotePreferences / 旧版 XSharedPreferences 均可）解析 */
    private fun fromMap(m: Map<String, *>): NetConfig {
        fun str(k: String, d: String): String = (m[k] as? String) ?: d
        fun bool(k: String, d: Boolean): Boolean = (m[k] as? Boolean) ?: d
        fun lng(k: String, d: Long): Long = (m[k] as? Long) ?: d
        val trusted = when (val v = m[Config.K_TRUSTED]) {
            is List<*> -> v.map { it.toString() }
            is String -> Config.stringToList(v)
            else -> emptyList()
        }
        val exclude = when (val v = m[Config.K_EXCLUDE]) {
            is List<*> -> v.map { it.toString() }
            is String -> Config.stringToList(v)
            else -> emptyList()
        }
        return NetConfig(
            enabled = bool(Config.K_ENABLED, false),
            alwaysOn = bool(Config.K_ALWAYS_ON, false),
            forceHttps = bool(Config.K_FORCE_HTTPS, true),
            blockPlainSocket = bool(Config.K_BLOCK_PLAIN, true),
            useDoh = bool(Config.K_USE_DOH, true),
            dohProvider = str(Config.K_DOH_PROVIDER, "cloudflare"),
            debugLog = bool(Config.K_DEBUG_LOG, true),
            dohCustom = str(Config.K_DOH_CUSTOM, ""),
            trusted = trusted.toSet(), exclude = exclude.toSet(),
            stateOnWifi = bool(Config.K_STATE_ON_WIFI, false),
            stateSsid = str(Config.K_STATE_SSID, ""),
            stateTime = lng(Config.K_STATE_TIME, 0L)
        )
    }

    private fun parseJson(o: JSONObject): NetConfig {
        val trusted = ArrayList<String>()
        val exclude = ArrayList<String>()
        val ta = o.optJSONArray(Config.K_TRUSTED)
        if (ta != null) for (i in 0 until ta.length()) trusted.add(ta.optString(i))
        val ea = o.optJSONArray(Config.K_EXCLUDE)
        if (ea != null) for (i in 0 until ea.length()) exclude.add(ea.optString(i))
        return NetConfig(
            enabled = o.optBoolean(Config.K_ENABLED, false),
            alwaysOn = o.optBoolean(Config.K_ALWAYS_ON, false),
            forceHttps = o.optBoolean(Config.K_FORCE_HTTPS, true),
            blockPlainSocket = o.optBoolean(Config.K_BLOCK_PLAIN, true),
            useDoh = o.optBoolean(Config.K_USE_DOH, true),
            dohProvider = o.optString(Config.K_DOH_PROVIDER, "cloudflare"),
            debugLog = o.optBoolean(Config.K_DEBUG_LOG, true),
            dohCustom = o.optString(Config.K_DOH_CUSTOM, ""),
            trusted = trusted.toSet(), exclude = exclude.toSet(),
            stateOnWifi = o.optBoolean(Config.K_STATE_ON_WIFI, false),
            stateSsid = o.optString(Config.K_STATE_SSID, ""),
            stateTime = o.optLong(Config.K_STATE_TIME, 0L)
        )
    }
}
