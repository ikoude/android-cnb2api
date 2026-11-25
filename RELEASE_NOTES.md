## CNB2API Android 网关 v0.2.0

把 DSH 插件的 CNB2API 能力搬到安卓：手机常驻一个固定地址的 OpenAI 兼容网关 `http://<手机IP>:41729/v1`，其它 AI 客户端只需配置一次，工作区地址漂移、账号切换、额度耗尽换号都在网关背后完成。

### 核心能力

- 固定地址网关，支持 SSE 流式转发、图片消息、64 MiB 请求体
- 多账号按可用性择优；上游 401/402/429/5xx 同请求内自动换号，失败账号冷却 60 秒
- 保底线硬停 / 警戒线提示；额度含冻结账目，`remain` 为真实可用
- 工作区地址跟随与自动启动；前台服务保证锁屏/切后台后继续运行
- iOS 风格界面：仪表盘、账号、设置、日志四页，支持左右滑动与底栏排序
- 本地与 WebDAV 备份（scrypt + AES-256-GCM，与 DSH 插件格式兼容）
- Keystore 加密存储令牌/PROXY_KEY，`allowBackup=false`

### 本版本修复与优化

- 修复备份合并把现有账号仓库名小写的 bug
- 修复设置页监听地址切换不保存、端口输入每敲一字就重启网关的问题
- 地址枚举缓存与快照后台化，减少主线程负担
- 网关请求不再 `runBlocking` 阻塞；WebDAV 下载限长 16 MiB
- 移除 `material-icons-extended` 依赖，APK 从 14.5 MB 降到 4.2 MB

### 安装

下载 `app-release.apk` 直接安装，或自行构建：

```bash
./gradlew :app:assembleRelease
# 产物：app/build/outputs/apk/release/app-release.apk
```

首次启动会申请通知权限并拉起前台服务。设备需 Android 8.0（API 26）及以上。

### 验证

已在 Android 9 / 12 / 13 / 15 / 16 上完成回归：网关鉴权、状态接口、地址枚举、
通知权限（含拒绝场景）、前台服务类型、设置持久化均通过。

### 签名

Release 使用 v2 签名方案，证书指纹 SHA-256:
`31a7255c8419d8adfcf0c7924b3e091020b7869ea61a12a4bcef62bea21e7273`
