# CNB2API Android 发布前检查清单

## 阻塞项（发布前必须处理）

- [ ] **Release 签名**：当前 `:app:assembleRelease` 只生成 `app-release-unsigned.apk`，不能直接安装。需要配置 keystore / Play App Signing，并生成 `app-release.apk` 或 `.aab`。
- [ ] **版本号**：当前 `versionCode = 6`、`versionName = 0.2.2`，发布前按版本规范递增。
- [ ] **targetSdk**：当前 `targetSdk = 35`；若上 Google Play，需确认商店当前要求的 target API 并做升级/回归测试。
- [ ] **分发渠道**：确认是侧载 APK、企业分发还是应用商店；商店需要隐私政策、应用说明、图标/截图与内容分级。

## 已完成 / 已验证

- [x] Debug 构建、安装、启动；`/health` 200。
- [x] 后台/熄屏保活：前台服务 + PARTIAL WakeLock + 高性能 Wi‑Fi Lock；服务类型改为 `specialUse|dataSync`，避免 Android 15 dataSync 时长限制；熄屏后 `/health` 仍 200。
- [x] Android 16 上 `/proc/net/if_inet6` 被禁读的问题：改为 `ConnectivityManager.getAllNetworks()` + `LinkAddress.getFlags()` 过滤临时 IPv6。
- [x] 网关协议：`/v1/models`、非流式、流式 SSE、图片消息、`Transfer-Encoding: chunked` 已实测 200。
- [x] 网关 HTTP 状态行修复：不再出现 `HTTP/1.1 HTTP 200` 畸形响应。
- [x] 备份格式与插件兼容；本地备份/还原、WebDAV 上传/下载、恢复确认框。
- [x] 修复备份合并把 `GPU.3/api` 小写为 `gpu.3/api` 的仓库大小写 bug；恢复后 5 个账号仓库名保持原样。
- [x] 默认网关 key 改为首次安装随机生成；不再使用硬编码 key。
- [x] CNB token / PROXY_KEY / WebDAV 凭据存在 EncryptedSharedPreferences；`allowBackup=false` 且 dataExtractionRules 排除敏感数据。
- [x] 首次启动 `accounts.json` 明文迁移导入后会删除源文件。
- [x] 账号排序、运行时长持久化、地址（IPv4/IPv6、非临时地址）显示与复制。
- [x] 设置自动保存、WebDAV 独立草稿+保存按钮、切页清理输入法焦点。
- [x] 备份导出/导入使用系统文件选择器。
- [x] Lint：`0 errors`（剩 8 个非阻塞 warning：依赖可升级、Compose Modifier 参数顺序、v26 mipmap 目录）。
- [x] `security-crypto` 升级到稳定 1.1.0；WakeLock 超时检查已按常驻前台服务抑制。

## 版本矩阵实测结果（2026-11-25）

| 环境 | 版本 | 覆盖点 | 结果 |
|---|---|---|---|
| Redmi 23113RKC6C | Android 16 (API 36) | 前台服务 `specialUse`、双栈地址、深色模式、端口防抖重启 | 通过 |
| Unihertz Jelly 2E | Android 12 (API 31) | Haze `RenderEffect` 分界、小屏 480×854、FGS 旧类型回退 | 通过 |
| emulator mn-35 | Android 15 (API 35) | `dataSync` 限制规避、拒绝通知权限后服务存活、设置持久化 | 通过 |
| emulator mn-33 | Android 13 (API 33) | `POST_NOTIFICATIONS` 运行时申请（允许/拒绝两条路径） | 通过 |
| emulator mn-28 | Android 9 (API 28) | `minSdk` 附近兼容、Compose 低版本路径、Keystore | 通过 |

跨 API 28–36 的 `/health`、鉴权（401/200）、`/v1/models`、无账号 503、监听地址切换均已实测。复用命令：

```bash
$ANDROID_HOME/emulator/emulator -avd mn-35 -no-window -no-audio -gpu swiftshader_indirect
adb -s emulator-5554 forward tcp:41804 tcp:41729
```

## 仍需人工验证

- [x] **Android 版本矩阵**：已覆盖 9 / 12 / 13 / 15 / 16，未覆盖 8 / 10 / 11（无特殊 API 分支，风险低）。
- [ ] **OEM 后台限制**：小米、Unihertz 已测；华为/OPPO/vivo 的自启动与省电策略仍需实测。
- [ ] **网络矩阵**：Wi-Fi IPv4/IPv6、移动数据、Wi-Fi 与移动数据切换、AP 隔离/路由器防火墙、局域网另一台设备实际访问。
- [ ] **DNS/代理**：系统代理、VPN、Private DNS 对上游工作区连接的影响。
- [ ] **长时间运行**：前台服务连续运行数小时/跨天，检查内存、通知、额度轮询、断网重连。
- [ ] **额度边界**：无额、警戒线、保底线、冻结、额度恢复后的自动启动与网关拒绝原因。
- [ ] **备份恢复**：错误口令、损坏文件、插件生成备份/App 生成备份互导、WebDAV 404/超时、恢复后账号合并。
- [ ] **UI/可用性**：小屏（480×854）、大屏、横屏、字体放大、深色/浅色、输入法遮挡。
- [ ] **图片输入**：大图、base64、多图、远程 image URL；失败时上游错误是否正确透传。

## 上线前安全/合规

- [ ] 生成正式签名 keystore，离线保管；不要提交到仓库。
- [ ] 提供隐私政策：说明 CNB token、PROXY_KEY、WebDAV 密码仅存本机，以及请求会发往 CNB/工作区和 WebDAV。
- [ ] 检查日志不包含 token、PROXY_KEY、WebDAV 密码（目前请求日志只记录 method/URI/来源 IP）。
- [ ] 确认 `http://` WebDAV 的明文风险提示（已允许 cleartext 以兼容局域网 WebDAV，建议用户优先 HTTPS）。
- [ ] `/health` 不需要鉴权，确认这是可接受的设计；不要在 `/health` 暴露额度/账号信息。
- [ ] 评估 R8/minify、APK 体积、BouncyCastle/加密库的合规与许可证声明。

## 发布构建命令（签名配置完成后）

```bash
./gradlew :app:assembleRelease
# 或上架用：
./gradlew :app:bundleRelease
```

当前产物：
- Debug：`app/build/outputs/apk/debug/app-debug.apk`
- Release（已签名）：`app/build/outputs/apk/release/app-release.apk`
- 签名密钥：`keystore/cnb2api-release.jks` + `keystore.properties`（已加入 `.gitignore`，请离线备份；丢失将无法升级已安装应用）
