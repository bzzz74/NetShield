package com.qisi.netshield.common

import android.content.Context
import android.content.SharedPreferences
import com.qisi.netshield.NetShieldApp
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException

/**
 * 模块设置界面与被 Hook 应用之间的「共享配置」。
 *
 * 两条通道（设置侧同时写入，Hook 侧任一可用即可）：
 * 1) 主通道：LSPosed RemotePreferences（框架数据库，经 XposedService 写入、
 *    被 Hook 进程经 XposedModule.getRemotePreferences 读取）；
 * 2) 备用通道：配置 JSON 由 root（su）同步到 /data/local/tmp/netshield/config.json。
 */
object Config {

    const val MOD_PKG = "com.qisi.netshield"
    const val PREFS = "netshield_config"
    const val SHADOW_FILE = "/data/local/tmp/netshield/config.json"

    /** 网络状态超过该时长未刷新时，Hook 侧视为「状态不可信」，自动放弃强制加密 */
    const val STATE_FRESH_MS = 15 * 60 * 1000L

    // ---- 配置键 ----
    const val K_ENABLED = "enabled"
    const val K_ALWAYS_ON = "alwaysOn"
    const val K_FORCE_HTTPS = "forceHttps"
    const val K_BLOCK_PLAIN = "blockPlainSocket"
    const val K_USE_DOH = "useDoh"
    const val K_DOH_PROVIDER = "dohProvider"
    const val K_DOH_CUSTOM = "dohCustomUrl"
    const val K_DEBUG_LOG = "debugLog"
    const val K_TRUSTED = "trustedSsids"      // 换行分隔
    const val K_EXCLUDE = "excludePackages"   // 换行分隔
    const val K_STATE_ON_WIFI = "stateOnWifi"
    const val K_STATE_SSID = "stateSsid"
    const val K_STATE_TIME = "stateTime"

    /** DoH 提供商标识（与界面下拉框顺序一致） */
    val DOH_PROVIDERS = arrayOf("cloudflare", "google", "ali", "custom")

    fun prefs(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** 将配置序列化为 JSON，供备用通道使用 */
    fun toJson(p: SharedPreferences): String = JSONObject().apply {
        put(K_ENABLED, p.getBoolean(K_ENABLED, false))
        put(K_ALWAYS_ON, p.getBoolean(K_ALWAYS_ON, false))
        put(K_FORCE_HTTPS, p.getBoolean(K_FORCE_HTTPS, true))
        put(K_BLOCK_PLAIN, p.getBoolean(K_BLOCK_PLAIN, true))
        put(K_USE_DOH, p.getBoolean(K_USE_DOH, true))
        put(K_DEBUG_LOG, p.getBoolean(K_DEBUG_LOG, true))
        put(K_DOH_PROVIDER, p.getString(K_DOH_PROVIDER, "cloudflare") ?: "cloudflare")
        put(K_DOH_CUSTOM, p.getString(K_DOH_CUSTOM, "") ?: "")
        put(K_TRUSTED, stringToList(p.getString(K_TRUSTED, "")))
        put(K_EXCLUDE, stringToList(p.getString(K_EXCLUDE, "")))
        put(K_STATE_ON_WIFI, p.getBoolean(K_STATE_ON_WIFI, false))
        put(K_STATE_SSID, p.getString(K_STATE_SSID, "") ?: "")
        put(K_STATE_TIME, p.getLong(K_STATE_TIME, 0L))
    }.toString()

    /** 设置侧统一出口：写远程配置（主）+ su 同步文件（备），均为尽力而为 */
    fun syncOut(ctx: Context) {
        val p = prefs(ctx)

        // 1) RemotePreferences —— LSPosed 框架数据库
        try {
            NetShieldApp.service?.getRemotePreferences(PREFS)?.edit()?.let { e ->
                e.putBoolean(K_ENABLED, p.getBoolean(K_ENABLED, false))
                e.putBoolean(K_ALWAYS_ON, p.getBoolean(K_ALWAYS_ON, false))
                e.putBoolean(K_FORCE_HTTPS, p.getBoolean(K_FORCE_HTTPS, true))
                e.putBoolean(K_BLOCK_PLAIN, p.getBoolean(K_BLOCK_PLAIN, true))
                e.putBoolean(K_USE_DOH, p.getBoolean(K_USE_DOH, true))
                e.putBoolean(K_DEBUG_LOG, p.getBoolean(K_DEBUG_LOG, true))
                e.putString(K_DOH_PROVIDER, p.getString(K_DOH_PROVIDER, "cloudflare"))
                e.putString(K_DOH_CUSTOM, p.getString(K_DOH_CUSTOM, ""))
                e.putString(K_TRUSTED, p.getString(K_TRUSTED, ""))
                e.putString(K_EXCLUDE, p.getString(K_EXCLUDE, ""))
                e.putBoolean(K_STATE_ON_WIFI, p.getBoolean(K_STATE_ON_WIFI, false))
                e.putString(K_STATE_SSID, p.getString(K_STATE_SSID, ""))
                e.putLong(K_STATE_TIME, p.getLong(K_STATE_TIME, 0L))
                e.apply()
            }
        } catch (t: Throwable) {
            // 框架服务未绑定（如模块未启用）时忽略
        }

        // 2) su 同步到 /data/local/tmp（备用通道）
        try {
            val json = toJson(p)
            val tmp = File(ctx.filesDir, "netshield_config.json")
            tmp.writeText(json)
            val cmd = "mkdir -p /data/local/tmp/netshield && " +
                    "cat '${tmp.absolutePath}' > $SHADOW_FILE && " +
                    "chmod 755 /data/local/tmp/netshield && " +
                    "chmod 644 $SHADOW_FILE"
            val proc = ProcessBuilder("su", "-c", cmd).redirectErrorStream(true).start()
            proc.outputStream.close()
            proc.inputStream.readBytes() // 吸掉输出避免阻塞
            try { proc.waitFor() } catch (e: InterruptedException) { Thread.currentThread().interrupt() }
        } catch (e: IOException) {
            // 无 root / su 被拒：忽略
        }
    }

    fun listToString(list: List<String>): String = list.filter { it.isNotBlank() }.joinToString("\n")

    fun stringToList(s: String?): List<String> =
        (s ?: "").split('\n', ',').map { it.trim() }.filter { it.isNotEmpty() }
}
