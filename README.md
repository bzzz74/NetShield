# NetShield —— 公共网络加密 LSPosed 模块

在检测到处于「公共/未知 Wi-Fi」时，通过 LSPosed 钩子强制设备上的应用加密联网：

| 保护层 | 做法 |
| --- | --- |
| 强制 HTTPS | 把应用内 `java.net.URL`、okhttp3 构造出的 `http://` 请求自动改写为 `https://` |
| 拦截明文连接 | 被保护应用向 **80 端口** 发起的裸 `Socket` 连接直接阻断 |
| DNS 防嗅探 | 应用发起的域名解析（`java.net.InetAddress`）改走 **DNS over HTTPS**（Cloudflare / Google / 阿里可切换），公共 Wi-Fi 上无法看到你访问的域名 |

## ⚠️ 工作原理与边界（重要，请先读）

- **不是 VPN**：它不建立隧道，而是在“被保护应用”自己的进程里加钩子，让绝大部分 HTTP/DNS 流量变成 TLS 加密流量。属于 LSPosed 模块的正当用法，无需签证、不消耗流量代理。
- **需在 LSPosed 中勾选作用域**：只对勾选了的应用生效（建议勾选浏览器、社交、支付、账号类 App，可多选；不要勾选系统进程）。
- **WebView 内核**流量（部分 App 内嵌页面）不走这些钩子，属于已知边界。
- 使用 **原生/非 Java 网络栈** 的特殊程序（极少）不覆盖。
- 强制 HTTPS 后，**只有 http 没有 https 的老网站/内网服务会无法访问**（可用「清空/编辑白名单」或关闭「强制 HTTPS」缓解）。局域网地址（127.x / 10.x / 172.16-31.x / 192.168.x）被自动放行，不受影响。
- 状态由本模块的前台服务实时写入；服务被杀或状态超过 15 分钟未刷新时，钩子会**自动停用**（默认安全，避免误伤）。

## 兼容性（v2.0 起为现代 API 模块）

- **v2.0 起已迁移到 libxposed 现代 API（`io.github.libxposed:api:102.0.0`，`module.prop` 声明 `minApiVersion=100 / targetApiVersion=102`）**，在 LSPosed 2.2.1 (API 102) 上不再显示 legacy 标签与废弃警告。
- 配置共享改为双通道：
  1. **主通道**：LSPosed RemotePreferences（框架数据库）——设置界面经 `XposedService` 写入，被 Hook 进程经 `getRemotePreferences` 读取；
  2. **备用通道**：su 同步 `/data/local/tmp/netshield/config.json`。
  任一通道可用即生效；LSPosed 环境必有 Root，兜底通道始终可用。
- v1.1（经典 API 版）曾使用 `xposedsharedprefs` / `XSharedPreferences` 机制，已在 v2.0 中完全移除。
- 模块只 Hook 被勾选应用的进程内代码，**不刺入系统进程**（`android` 包已被入口排除），不写任何系统分区。

### 自定义 DoH 服务器（v2.0 新增）

DoH 下拉框选择「自定义 DoH 服务器…」后，填入 HTTPS 的 JSON 风格接口地址（与 Cloudflare/Google 兼容，如 `https://dns.example.com/dns-query`）。模块会自动追加 `?name=<域名>&type=<A|AAAA>` 查询。地址非法时自动回落 Cloudflare。

## 构建（无需本地 Android 环境）

1. 在 GitHub 新建仓库，把本目录整体推上去（`.github/workflows/build.yml` 已就位）。
2. 打开 Actions 页面，等待 **Build APK** 完成。
3. 从 Artifacts 下载 `NetShield-apk`（已用 debug 签名，可直接安装）。

本地有环境时：`gradle :app:assembleRelease`。

## 使用步骤

1. 手机需已 Root 并装好 **LSPosed**（Zygisk 版即可）。
2. 安装本 APK。
3. 打开 LSPosed → 模块 → 启用 **NetShield** → 进入「作用域」勾选要保护的应用（**不要**勾选系统框架外的系统 App，无需勾选系统服务）。
4. 打开 NetShield App：
   - 打开「总开关」；
   - 点「把当前 Wi-Fi 加入白名单」把你家里的 Wi-Fi 加进去（建议授予「位置权限」以便读取 SSID 名称；不开权限时所有 Wi-Fi 一律视为公共网络，保护更严格）；
   - 需要更激进时勾「始终开启」（蜂窝网络也强制加密）。
5. 保持 App 发出的状态通知运行（前台服务负责同步网络状态）。
6. 连接公共 Wi-Fi 测试：浏览器强行访问 `http://` 站点会被升到 `https://`；故意走明文的 App 请求会被拦截并可在 LSPosed 日志中看到 `NetShield:` 记录。

## 配置项速查

| 开关 | 作用 |
| --- | --- |
| 总开关 | 关闭后钩子完全静默 |
| 始终开启 | 任何网络（含蜂窝）都强制加密 |
| 强制 HTTPS | `http://` → `https://` 自动改写 |
| 拦截明文连接 | 80 端口明文 Socket 直接失败 |
| DNS 加密 | DoH 提供商：Cloudflare / Google / 阿里 |
| Wi-Fi 白名单 | 名单中的 SSID 按受信任网络处理（本机/内网地址永远放行） |

## 故障排查

- **所有 App 都没反应** → 检查 LSPosed 作用域是否勾选、总开关是否打开、被保护 App 是否需要重启（刷新进程）。
- **只在公共 Wi-Fi 生效，家里不生效** → 正常设计；确认服务通知在跑、状态时间新鲜（15 分钟阈值）。
- **某 App 打不开内容** → 可能该站无 HTTPS 或用 WebView。关掉「拦截明文连接」或「强制 HTTPS」二选一排查。
- **su 同步失败提示**（`/data/local/tmp` 兜底）→ 正常，主通道 XSharedPreferences 已在多数场景生效。
