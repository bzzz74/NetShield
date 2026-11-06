package com.qisi.netshield

import android.app.Application
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper

/**
 * 模块 App 进程：向 LSPosed 框架注册服务监听，
 * 拿到 XposedService 后即可读写 RemotePreferences（框架数据库中的跨进程配置）。
 */
class NetShieldApp : Application(), XposedServiceHelper.OnServiceListener {

    companion object {
        @Volatile
        var service: XposedService? = null
            private set
    }

    override fun onCreate() {
        super.onCreate()
        XposedServiceHelper.registerListener(this)
    }

    override fun onServiceBind(service: XposedService) {
        NetShieldApp.service = service
    }

    override fun onServiceDied(service: XposedService) {
        NetShieldApp.service = null
    }
}
