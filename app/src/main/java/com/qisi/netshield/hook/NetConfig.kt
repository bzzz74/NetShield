package com.qisi.netshield.hook

import com.qisi.netshield.common.Config

/**
 * Hook 侧使用的配置快照（现代 API：来自 RemotePreferences 或 /data/local/tmp 兜底文件）。
 */
data class NetConfig(
    val enabled: Boolean,
    val alwaysOn: Boolean,
    val forceHttps: Boolean,
    val blockPlainSocket: Boolean,
    val useDoh: Boolean,
    val dohProvider: String,
    val debugLog: Boolean = true,
    val dohCustom: String = "",
    val trusted: Set<String> = emptySet(),
    val exclude: Set<String> = emptySet(),
    val stateOnWifi: Boolean = false,
    val stateSsid: String = "",
    val stateTime: Long = 0L
) {

    private fun ssidTrusted(): Boolean {
        val cur = stateSsid.trim().removeSurrounding("\"").lowercase()
        if (cur.isEmpty()) return false // 状态未知 → 视为不安全（保守处理）
        return trusted.any { it.trim().removeSurrounding("\"").lowercase() == cur }
    }

    /** 当前是否处于「公共/未知网络」：alwaysOn 或（处于 Wi-Fi 且 SSID 不在白名单） */
    fun onPublicNetwork(): Boolean {
        if (alwaysOn) return true
        if (System.currentTimeMillis() - stateTime > Config.STATE_FRESH_MS) return false
        if (!stateOnWifi) return false
        return !ssidTrusted()
    }

    /** 该 App 当前是否应被强制加密保护 */
    fun applies(pkg: String): Boolean =
        enabled && pkg !in exclude && onPublicNetwork()
}
