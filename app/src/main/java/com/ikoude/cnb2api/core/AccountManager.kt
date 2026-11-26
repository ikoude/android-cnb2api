package com.ikoude.cnb2api.core

import com.ikoude.cnb2api.data.AccountConfig
import com.ikoude.cnb2api.data.AppSettings
import com.ikoude.cnb2api.data.AppSnapshot
import com.ikoude.cnb2api.data.GatewayStats
import com.ikoude.cnb2api.data.SecureStore
import com.ikoude.cnb2api.net.CnbClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

private data class Cooldown(val until: Long, val reason: String)
private const val COOLDOWN_MS = 60_000L
// 预编译正则与线程安全的时间格式器，避免每写一条日志都重新编译/新建对象。
private val ERROR_RE = Regex("失败|错误|异常")
private val WARN_RE = Regex("已用尽|冷却|不可用|低于|超时|拒绝")
private val TIME_FMT = java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss", java.util.Locale.US)

/** 账号集合 + 巡检节奏 + 网关冷却簿。网关只问它「现在该用谁」。 */
class AccountManager(private val store: SecureStore) {

    private val cnb = CnbClient()
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile var settings: AppSettings = store.loadSettings()
        private set

    /**
     * 用「不可变列表 + volatile 引用」整体替换，避免巡检线程（Dispatchers.Default）遍历时
     * 与主线程 configure 的 clear()/addAll() 撞车（ConcurrentModificationException）。
     */
    @Volatile private var monitors: List<Monitor> = emptyList()
    private val cooldowns = ConcurrentHashMap<String, Cooldown>()
    private val logs = Collections.synchronizedList(mutableListOf<com.ikoude.cnb2api.data.LogLine>())

    /** 由网关服务注入，避免 core 反向依赖 server。 */
    @Volatile var statsProvider: () -> GatewayStats = { GatewayStats() }

    private var pollJob: Job? = null

    init {
        configure(store.loadAccounts(), settings)
    }

    // 连续重复日志合并计数，避免「额度已用尽」这类每轮都写的提示把 200 条缓冲刷满、挤掉真正的错误。
    private var lastLogBase: String? = null
    private var lastLogLevel: com.ikoude.cnb2api.data.LogLevel? = null
    private var lastLogRepeat = 0

    fun log(line: String, level: com.ikoude.cnb2api.data.LogLevel? = null) {
        val lv = level ?: when {
            ERROR_RE.containsMatchIn(line) -> com.ikoude.cnb2api.data.LogLevel.ERROR
            WARN_RE.containsMatchIn(line) -> com.ikoude.cnb2api.data.LogLevel.WARN
            else -> com.ikoude.cnb2api.data.LogLevel.INFO
        }
        synchronized(logs) {
            val now = java.time.LocalTime.now().format(TIME_FMT)
            if (line == lastLogBase && lv == lastLogLevel && logs.isNotEmpty()) {
                lastLogRepeat++
                logs[logs.size - 1] = com.ikoude.cnb2api.data.LogLine(
                    now, "$line（重复 $lastLogRepeat 次）", lv,
                )
            } else {
                lastLogBase = line
                lastLogLevel = lv
                lastLogRepeat = 1
                logs.add(com.ikoude.cnb2api.data.LogLine(now, line, lv))
                while (logs.size > 200) logs.removeAt(0)
            }
        }
    }

    /** 运行起点持久化，保证 App 重启后不把“运行时长”清零。 */
    fun persistRunStarted(identity: String, startedAt: Long) {
        val current = monitors
        val list = current.map { m ->
            if (m.identity == identity) m.account.copy(runStartedAt = startedAt) else m.account
        }
        store.saveAccounts(list)
        current.forEach { m ->
            if (m.identity == identity) m.account = m.account.copy(runStartedAt = startedAt)
        }
    }

    fun configure(accounts: List<AccountConfig>, next: AppSettings) {
        settings = next
        store.saveAccounts(accounts)
        store.saveSettings(next)
        val current = monitors
        val nextMonitors = mutableListOf<Monitor>()
        for (acc in accounts) {
            val id = "${Monitor.fingerprint(acc.token)}@${acc.normalizedRepo}"
            val old = current.firstOrNull { it.identity == id }
            if (old != null && old.account.token == acc.token && old.account.branch == acc.branch && old.account.port == acc.port) {
                // 只改 PROXY_KEY 时沿用旧监视器，但必须把新密钥写进去，否则网关会用旧密钥请求。
                old.updateProxyKey(acc.proxyKey)
                nextMonitors.add(old)
            } else {
                nextMonitors.add(Monitor(acc, cnb, { settings }, ::log, ::persistRunStarted))
            }
        }
        // 整体替换 volatile 引用，读线程要么看到旧的完整列表，要么看到新的完整列表。
        monitors = nextMonitors.toList()
        log("配置已更新 · ${monitors.size} 个账号")
        scope.launch {
            monitors.forEach { it.sync("configure") }
        }
    }

    /** 仅更新设置并落盘，不重建监视器（避免每次微调都触发一轮工作区巡检）。 */
    fun updateSettingsOnly(next: AppSettings) {
        settings = next
        store.saveSettings(next)
    }

    fun accounts(): List<AccountConfig> = monitors.map { it.account }

    fun all(): List<Monitor> = monitors.toList()

    fun startPolling() {
        if (pollJob?.isActive == true) return
        pollJob = scope.launch {
            while (isActive) {
                delay(settings.checkSecondsSafe * 1000L)
                monitors.forEach { m -> m.sync("tick") }
            }
        }
    }

    fun stopPolling() {
        pollJob?.cancel()
        pollJob = null
    }

    fun pickBest(): Monitor? = AccountPicker.best(monitors, settings.creditFloorPercent)

    /** 网关真正能派活的账号：低于保底线、不可达、已损坏的一律不返回。 */
    fun pickUsable(): Monitor? = pickBest()?.takeIf { AccountPicker.canServe(it, settings.creditFloorPercent) }

    fun fallbacks(exclude: Monitor?): List<Monitor> {
        val floor = settings.creditFloorPercent
        return monitors.filter { it !== exclude && AccountPicker.canServe(it, floor) }
    }

    fun findByWanted(wanted: String): Monitor? {
        val w = wanted.trim()
        if (w.isBlank()) return null
        return monitors.firstOrNull { it.account.normalizedRepo == w || it.label == w }
    }

    fun cooldownOf(m: Monitor): Pair<Long, String> {
        val key = m.identity
        val row = cooldowns[key] ?: return 0L to ""
        if (row.until <= System.currentTimeMillis()) {
            cooldowns.remove(key)
            return 0L to ""
        }
        return (row.until - System.currentTimeMillis()) to row.reason
    }

    fun penalize(m: Monitor, reason: String, cooldownMs: Long = COOLDOWN_MS) {
        cooldowns[m.identity] = Cooldown(System.currentTimeMillis() + cooldownMs, reason)
        log("账号 ${m.label} 进入冷却 ${cooldownMs / 1000}s · ${reason.take(120)}")
    }

    /**
     * 纯连接失败（工作区重启/地址漂移/手机网络抖动）：只短暂降权，立即失效旧地址并触发后台重同步，
     * 避免沿用失效地址持续报错。
     */
    fun noteUpstreamFailure(m: Monitor, reason: String) {
        penalize(m, reason, 10_000L)
        m.invalidateEndpoint()
        scope.launch { m.sync("upstream-fail") }
    }

    fun reward(m: Monitor) { cooldowns.remove(m.identity) }

    fun startAccount(identity: String) {
        val m = monitors.firstOrNull { it.identity == identity } ?: return
        scope.launch {
            m.sync("manual")
            if (m.startIfAllowed("manual", true)) {
                m.waitUntilReady(90_000)
                m.sync("after-start")
            }
        }
    }

    fun refreshAccount(identity: String) {
        val m = monitors.firstOrNull { it.identity == identity } ?: return
        scope.launch { m.sync("probe") }
    }

    fun refreshAll() {
        scope.launch { monitors.forEach { it.sync("probe") } }
    }

    fun snapshot(): AppSnapshot {
        val active = pickUsable()
        val now = System.currentTimeMillis()
        val rows = monitors.map { m ->
            val (ms, reason) = cooldownOf(m)
            // 传绝对结束时间而非剩余毫秒：冷却期间快照不再每秒变化，StateFlow 可以合并掉。
            val until = if (ms > 0) now + ms else 0L
            m.snapshot(active === m, until, reason)
        }
        return AppSnapshot(
            accounts = rows,
            stats = statsProvider(),
            settings = settings,
            logs = synchronized(logs) { logs.toList() },
        )
    }
}
