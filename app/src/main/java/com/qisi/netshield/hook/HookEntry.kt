package com.qisi.netshield.hook

import android.util.Log
import com.qisi.netshield.common.Config
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedInterface.ExceptionMode
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam
import java.io.IOException
import java.lang.reflect.Executable
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL

/**
 * 模块入口（META-INF/xposed/java_init.list 指向本类，现代 libxposed API 100-102）。
 * 对被 LSPosed 作用域勾选的每个应用进程分别安装以下钩子：
 *
 * 1) java.net.URL 构造：http:// 改写为 https://（强制加密）
 * 2) java.net.Socket 构造：拦截指向 80 端口的明文连接（异常直通给调用方）
 * 3) okhttp3（HttpUrl.parse/get、Request.Builder.url）：同样重写为 https
 * 4) InetAddress.getByName/getAllByName：DNS 改走 DoH（失败自动回退系统 DNS）
 */
class HookEntry : XposedModule() {

    override fun onPackageLoaded(param: PackageLoadedParam) {
        // 实际安装放到 onPackageReady（拿到 app classloader）
    }

    override fun onPackageReady(param: PackageReadyParam) {
        val pkg = param.packageName
        // 不处理自身与系统进程
        if (pkg == Config.MOD_PKG || pkg == "android") return
        if (!param.isFirstPackage) return

        ModuleConfig.xposed = this
        NetHooks.install(this, pkg, param.classLoader)
    }
}

object NetHooks {

    private const val TAG = "NetShield"

    fun install(xp: XposedInterface, pkg: String, cl: ClassLoader) {

        // ---------- 1. java.net.URL 构造器重写 ----------
        runCatching {
            for (ctor in URL::class.java.declaredConstructors) {
                xp.hook(ctor).intercept { chain ->
                    val cfg = ModuleConfig.load()
                    if (!cfg.applies(pkg) || !cfg.forceHttps) return@intercept chain.proceed()
                    val args = chain.args.toTypedArray()
                    var changed = false
                    for (i in args.indices) {
                        val s = args[i] as? String ?: continue
                        val r = rewrite(s) ?: continue
                        args[i] = r
                        changed = true
                        if (cfg.debugLog) xp.log(Log.INFO, TAG, "[$pkg] URL: $s -> $r")
                    }
                    if (changed) chain.proceed(args) else chain.proceed()
                }
            }
        }.onFailure { xp.log(Log.ERROR, TAG, "hook URL fail: $it") }

        // ---------- 2. 拦截明文 Socket（80 端口） ----------
        runCatching {
            for (ctor in Socket::class.java.declaredConstructors) {
                xp.hook(ctor)
                    .setExceptionMode(ExceptionMode.PASSTHROUGH)
                    .intercept { chain ->
                        val cfg = ModuleConfig.load()
                        if (cfg.applies(pkg) && cfg.blockPlainSocket && portOf(chain.args) == 80) {
                            xp.log(Log.INFO, TAG, "[$pkg] blocked plain socket to port 80")
                            // 异常直通：跳过原构造并抛给调用方
                            throw IOException("NetShield: public network, plain HTTP on port 80 blocked")
                        }
                        chain.proceed()
                    }
            }
        }.onFailure { xp.log(Log.ERROR, TAG, "hook Socket fail: $it") }

        // ---------- 3. okhttp3 ----------
        runCatching {
            for (m in methodsOf(cl, "okhttp3.HttpUrl", "parse", "get")) {
                xp.hook(m).intercept { chain ->
                    val cfg = ModuleConfig.load()
                    if (!cfg.applies(pkg) || !cfg.forceHttps) return@intercept chain.proceed()
                    val s = chain.getArg(0) as? String ?: return@intercept chain.proceed()
                    val r = rewrite(s) ?: return@intercept chain.proceed()
                    if (cfg.debugLog) xp.log(Log.INFO, TAG, "[$pkg] okHttp.${m.name}: $s -> $r")
                    chain.proceed(arrayOf<Any>(r))
                }
            }
        }.onFailure { /* 目标应用不使用 okhttp 时属正常情况，忽略 */ }

        runCatching {
            for (m in methodsOf(cl, "okhttp3.Request\$Builder", "url")) {
                xp.hook(m).intercept { chain ->
                    val cfg = ModuleConfig.load()
                    if (!cfg.applies(pkg) || !cfg.forceHttps) return@intercept chain.proceed()
                    val args = chain.args.toTypedArray()
                    var replaced = false
                    for (i in args.indices) {
                        when (val a = args[i]) {
                            is String -> rewrite(a)?.let {
                                args[i] = it; replaced = true
                                if (cfg.debugLog) xp.log(Log.INFO, TAG, "[$pkg] okHttp.req: $a -> $it")
                            }
                            is URL -> rewrite(a.toString())?.let {
                                args[i] = URL(it); replaced = true
                                if (cfg.debugLog) xp.log(Log.INFO, TAG, "[$pkg] okHttp.req(URL): $a -> $it")
                            }
                        }
                    }
                    if (replaced) chain.proceed(args) else chain.proceed()
                }
            }
        }.onFailure { /* 同上，无 okhttp 则忽略 */ }

        // ---------- 4. DNS over HTTPS ----------
        runCatching {
            for (m in InetAddress::class.java.declaredMethods) {
                if (m.name != "getByName" && m.name != "getAllByName") continue
                if (m.parameterTypes.size != 1 || m.parameterTypes[0] != String::class.java) continue
                val returnArray = m.name == "getAllByName"
                xp.hook(m).intercept { chain ->
                    val cfg = ModuleConfig.load()
                    if (!cfg.applies(pkg) || !cfg.useDoh) return@intercept chain.proceed()
                    val host = chain.getArg(0) as? String ?: return@intercept chain.proceed()
                    val ips = DoHClient.resolve(host, cfg.dohProvider, cfg.dohCustom)
                        ?: return@intercept chain.proceed() // DoH 失败回落系统 DNS
                    if (cfg.debugLog) xp.log(Log.INFO, TAG, "[$pkg] DoH(host=$host) -> ${ips.size} ips")
                    if (returnArray) ips.toTypedArray() else ips.firstOrNull()
                }
            }
        }.onFailure { xp.log(Log.ERROR, TAG, "hook InetAddress fail: $it") }
    }

    /** 反射找出指定类中指定名字的方法；类不存在（如无 okhttp）返回空列表 */
    private fun methodsOf(cl: ClassLoader, className: String, vararg names: String): List<Executable> = try {
        cl.loadClass(className).declaredMethods
            .filter { it.name in names }
            .toList()
    } catch (t: Throwable) {
        emptyList()
    }

    /** http:// 改写为 https://，跳过本机/内网地址；无需改写返回 null */
    private fun rewrite(spec: String): String? {
        if (!spec.startsWith("http://")) return null
        val rest = spec.removePrefix("http://")
        val host = rest.substringBefore('/').substringBefore(':').lowercase()
        if (isLocal(host)) return null
        return "https://$rest"
    }

    private fun isLocal(host: String): Boolean {
        if (host == "localhost" || host == "::1" || host.endsWith(".local")) return true
        if (host.startsWith("127.") || host.startsWith("10.") || host.startsWith("192.168.")) return true
        // 172.16.0.0 - 172.31.255.255 RFC1918 private range
        if (host.startsWith("172.")) {
            val second = host.substringAfter("172.").substringBefore('.').toIntOrNull() ?: -1
            if (second in 16..31) return true
        }
        return false
    }

    private fun portOf(args: List<Any?>): Int {
        for (a in args) {
            when (a) {
                is Int -> if (a == 80) return 80
                is InetSocketAddress -> return a.port
            }
        }
        return -1
    }
}
