package com.qisi.netshield.ui

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import com.qisi.netshield.common.Config
import com.qisi.netshield.R

/**
 * 前台服务：持续监测当前网络，并把「是否处于 Wi-Fi / 当前 SSID / 时间戳」
 * 写入共享配置，供被 Hook 应用进程读取。
 * 服务死亡（手机重启/被杀）后状态变陈旧，Hook 自动停用（安全默认值）。
 */
class NetworkMonitorService : Service() {

    private val handler = Handler(Looper.getMainLooper())

    private val tick = object : Runnable {
        override fun run() {
            updateState()
            handler.postDelayed(this, 2 * 60 * 1000L)
        }
    }

    private val wifiCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = updateState()
        override fun onLost(network: Network) = updateState()
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) = updateState()
    }

    private val defaultCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = updateState()
        override fun onLost(network: Network) = updateState()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIF_ID, buildNotification(this, ""))
        handler.post(tick)

        val cm = getSystemService(ConnectivityManager::class.java)
        cm?.registerNetworkCallback(
            android.net.NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(), wifiCallback)
        cm?.registerNetworkCallback(
            android.net.NetworkRequest.Builder().build(), defaultCallback)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        updateState()
        return START_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(tick)
        val cm = getSystemService(ConnectivityManager::class.java)
        runCatching { cm?.unregisterNetworkCallback(wifiCallback) }
        runCatching { cm?.unregisterNetworkCallback(defaultCallback) }
        Config.syncOut(this)
        super.onDestroy()
    }

    private fun updateState() {
        val cm = getSystemService(ConnectivityManager::class.java) ?: return
        val network = cm.activeNetwork
        val caps = network?.let { cm.getNetworkCapabilities(it) }
        val onWifi = caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true

        var ssid = ""
        if (onWifi) {
            runCatching {
                val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                ssid = wm?.connectionInfo?.ssid ?: ""
            }
        }
        ssid = ssid.removeSurrounding("\"")

        Config.prefs(this).edit()
            .putBoolean(Config.K_STATE_ON_WIFI, onWifi)
            .putString(Config.K_STATE_SSID, ssid)
            .putLong(Config.K_STATE_TIME, System.currentTimeMillis())
            .apply()
        Config.syncOut(this)

        startForeground(NOTIF_ID, buildNotification(this, ssid))
    }

    private fun buildNotification(ctx: Context, ssid: String): Notification {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            nm?.createNotificationChannel(
                NotificationChannel(CH_ID, "NetShield 状态", NotificationManager.IMPORTANCE_LOW))
        }
        val text = if (ssid.isBlank()) "正在监测网络状态…" else "当前 Wi-Fi：$ssid"
        return NotificationCompat.Builder(ctx, CH_ID)
            .setSmallIcon(R.drawable.ic_shield)
            .setContentTitle("公共网络加密保护运行中")
            .setContentText(text)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val CH_ID = "netshield_monitor"
        private const val NOTIF_ID = 1

        fun start(ctx: Context) {
            val i = Intent(ctx, NetworkMonitorService::class.java)
            androidx.core.content.ContextCompat.startForegroundService(ctx, i)
        }
    }
}

private fun Context.hasPermission(p: String) =
    androidx.core.content.ContextCompat.checkSelfPermission(this, p) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED

private fun Context.needLocationPermission(): Boolean =
    !hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)
