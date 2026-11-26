package com.ikoude.cnb2api.core

import com.ikoude.cnb2api.data.AccountConfig
import com.ikoude.cnb2api.data.AppSettings
import com.ikoude.cnb2api.data.Ledger
import com.ikoude.cnb2api.data.Lifecycle
import com.ikoude.cnb2api.data.WorkState
import com.ikoude.cnb2api.net.ApiException
import com.ikoude.cnb2api.net.CnbClient
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.security.MessageDigest

private const val QUOTA_TTL = 60_000L
private const val HEALTH_TTL = 15_000L
private const val START_COOLDOWN = 60_000L

/** 一个账号一个实例：查明工作区事实、限流地查额度、按闸门启动。 */
class Monitor(
    var account: AccountConfig,
    private val cnb: CnbClient,
    private val settings: () -> AppSettings,
    private val log: (String) -> Unit,
    private val onRunStarted: (String, Long) -> Unit = { _, _ -> },
) {
    val identity: String = "${fingerprint(account.token)}@${account.normalizedRepo}"
    val label: String get() = if (username.isNotBlank()) "$username | ${account.normalizedRepo}" else account.normalizedRepo.ifBlank { "未配置" }

    @Volatile var lifecycle = Lifecycle.ABSENT
    @Volatile var url = ""
    @Volatile var reachable = false
    @Volatile var creditExhausted = false
    @Volatile var broken = false
    @Volatile var starting = false
    @Volatile var noWorkspace = false
    @Volatile var username = ""
    @Volatile var workspaceId = ""
    @Volatile var workspaceBranch = ""
    @Volatile var runningSince = account.runStartedAt
    @Volatile var lastError: String? = null
    @Volatile var lastCheckedAt = 0L
    @Volatile var ledger: Ledger? = null
    @Volatile var lastStartBlockedReason: String? = null

    private var quotaAt = 0L
    private var lastHealthAt = 0L
    private var lastStartAt = 0L
    // 上次已记过的“被拦住”原因；相同原因不重复写日志，避免每轮巡检刷屏。
    private var lastBlockedLogReason: String? = null
    private val syncMutex = Mutex()
    private val quotaMutex = Mutex()
    private val startMutex = Mutex()

    private val gatewayPort: Int get() = account.port.takeIf { it > 0 } ?: settings().port
    private val startBranch: String get() = account.branch.ifBlank { settings().branch }
    private val autoStart: Boolean get() = settings().autoStart

    suspend fun sync(reason: String) {
        if (syncMutex.isLocked) return
        syncMutex.withLock { runSync(reason) }
    }

    private suspend fun runSync(reason: String) {
        lastCheckedAt = System.currentTimeMillis()
        if (account.token.isBlank()) {
            broken = true
            lastError = "尚未填写 CNB 访问令牌"
            return
        }
        if (!account.normalizedRepo.contains("/")) {
            broken = true
            lastError = "仓库需要 组织/仓库，当前是 ${account.normalizedRepo}"
            return
        }
        try {
            val rows = cnb.listWorkspaces(account.token, account.normalizedRepo)
            val row = cnb.pickWorkspace(rows)
            val nextLifecycle = cnb.lifecycleOf(row)
            lifecycle = nextLifecycle
            // 不能用 create_time：那是工作区创建时间，不是本次运行开始时间。
            // 平台没有明确的 start_time 时，以本 App 首次观察到 RUNNING 的时刻为起点。
            if (nextLifecycle == Lifecycle.RUNNING) {
                val platformStart = row?.let { parseStartTime(it) } ?: 0L
                if (platformStart > 0) {
                    if (runningSince != platformStart) onRunStarted(identity, platformStart)
                    runningSince = platformStart
                } else if (runningSince <= 0L) {
                    runningSince = System.currentTimeMillis()
                    onRunStarted(identity, runningSince)
                }
            } else if (runningSince != 0L) {
                runningSince = 0L
                onRunStarted(identity, 0L)
            }

            if (row == null) {
                clearWorkspace()
                broken = !cnb.repoExists(account.token, account.normalizedRepo)
                if (broken) {
                    lastError = "仓库 ${account.normalizedRepo} 不存在，或令牌无权访问"
                    return
                }
                lastError = "该仓库还没有工作区"
                if (autoStart) {
                    noWorkspace = true
                    startIfAllowed("create")
                }
                return
            }

            noWorkspace = false
            workspaceId = row.optString("sn", "")
            workspaceBranch = row.optString("branch", "").ifBlank { startBranch }
            broken = false
            if (username.isBlank()) username = cnb.whoAmI(account.token) ?: ""

            if (lifecycle == Lifecycle.PENDING) {
                starting = true
                url = ""
                reachable = false
                lastError = "已提交启动 · 等待工作区就绪"
                return
            }
            if (lifecycle != Lifecycle.RUNNING) {
                url = ""
                reachable = false
                starting = false
                lastError = "工作区已停止"
                if (autoStart) {
                    if (!startIfAllowed("restart")) {
                        lastStartBlockedReason?.let { lastError = it }
                    }
                } else {
                    quota().let { creditExhausted = QuotaMath.isExhausted(it) }
                }
                return
            }

            starting = false
            // 每次巡检都重新解析地址：工作区重启后地址会变，复用旧地址会导致后续请求连不上。
            val next = cnb.resolveUrl(account.token, account.normalizedRepo, workspaceId, gatewayPort)
            if (next.isNullOrBlank()) {
                reachable = false
                lastError = "工作区在运行，但取不到网关地址 · 日志可能尚未输出，或日志接口暂时不可用"
                return
            }
            if (next != url) lastHealthAt = 0
            url = next
            reachable = probe()
            lastError = if (reachable) null else "工作区在运行，但网关地址连不上"
            if (reachable) {
                if (System.currentTimeMillis() - quotaAt >= QUOTA_TTL) quota()
            }
        } catch (e: ApiException) {
            reachable = false
            lastError = cnb.describe(e)
            if (e.permission || e.status == 401) broken = true
        } catch (e: Exception) {
            reachable = false
            lastError = cnb.describe(e)
        }
    }

    private fun clearWorkspace() {
        workspaceId = ""
        workspaceBranch = ""
        runningSince = 0
        onRunStarted(identity, 0L)
        url = ""
        reachable = false
        starting = false
    }

    /** 只认明确的“启动时间”字段；create_time 是工作区创建时间，不能当运行时长。 */
    private fun parseStartTime(row: org.json.JSONObject): Long {
        for (key in listOf("start_time", "startTime", "started_at", "startedAt", "last_start_time", "lastStartedAt")) {
            val raw = row.opt(key) ?: continue
            val v = when (raw) {
                is Number -> raw.toLong()
                is String -> raw.toLongOrNull() ?: try { java.time.Instant.parse(raw).toEpochMilli() } catch (e: Exception) { 0L }
                else -> 0L
            }
            if (v > 0) return v
        }
        return 0L
    }

    suspend fun probe(force: Boolean = false): Boolean {
        if (url.isBlank()) return false
        if (!force && reachable && System.currentTimeMillis() - lastHealthAt < HEALTH_TTL) return true
        val ok = cnb.healthy(url, 8000)
        lastHealthAt = System.currentTimeMillis()
        return ok
    }

    suspend fun quota(force: Boolean = false): Ledger {
        val org = account.normalizedRepo.substringBefore("/")
        if (org.isBlank() || account.token.isBlank()) return Ledger(failures = listOf("令牌或仓库尚未配置"))
        if (!force && ledger != null && System.currentTimeMillis() - quotaAt < QUOTA_TTL) return ledger!!
        return quotaMutex.withLock {
            // 拿到锁后重查缓存：并发调用者应当等这次真实查询，而不是拿到空账。
            if (!force && ledger != null && System.currentTimeMillis() - quotaAt < QUOTA_TTL) {
                return@withLock ledger!!
            }
            val raw = cnb.readQuota(account.token, org, if (force) 20_000 else 8_000)
            ledger = raw
            quotaAt = System.currentTimeMillis()
            creditExhausted = QuotaMath.isExhausted(raw)
            raw
        }
    }

    suspend fun startIfAllowed(intent: String, forceQuota: Boolean = false): Boolean {
        if (startMutex.isLocked) return false
        return startMutex.withLock {
            lastStartBlockedReason = null
            if (System.currentTimeMillis() - lastStartAt < START_COOLDOWN) return@withLock true
            beginStart(intent, forceQuota)
        }
    }

    /** 同一原因只记一次；状态变化或解除后再记，避免每轮巡检重复刷屏。 */
    private fun logBlockedOnce(reason: String) {
        lastStartBlockedReason = reason
        if (lastBlockedLogReason != reason) {
            lastBlockedLogReason = reason
            log("[$label] $reason")
        }
    }

    private suspend fun beginStart(intent: String, forceQuota: Boolean): Boolean {
        val q = try { quota(forceQuota) } catch (e: Exception) { null }
        if (QuotaMath.isExhausted(q)) {
            creditExhausted = true
            logBlockedOnce("AI credits 已用尽 · 额度恢复后会自动启动")
            return false
        }
        val floor = settings().creditFloorPercent
        val ratio = q?.credit?.remainPercent
        if (floor > 0 && ratio != null && ratio < floor) {
            logBlockedOnce("余额低于保底线 $floor% · 停止使用这个账号（可用 ${String.format(java.util.Locale.US, "%.2f", ratio)}%）")
            return false
        }
        // 不再被拦，重置去重状态，允许下次再次拦截时重新记录。
        lastBlockedLogReason = null
        return try {
            cnb.startWorkspace(account.token, account.normalizedRepo, startBranch)
            lastStartAt = System.currentTimeMillis()
            starting = true
            lastError = "已提交启动 · 网关通常 30–90 秒内就绪"
            log("[$label] ${if (intent == "create") "正在创建工作区" else "工作区已停止 · 正在启动"}")
            true
        } catch (e: ApiException) {
            lastError = if (e.permission) {
                "无法自动启动 · 令牌缺少 repo-cnb-trigger:rw · 地址仍会跟随"
            } else {
                "启动失败 · ${cnb.describe(e)}"
            }
            log("[$label] $lastError")
            false
        } catch (e: Exception) {
            lastError = "启动失败 · ${cnb.describe(e)}"
            log("[$label] $lastError")
            false
        }
    }

    suspend fun waitUntilReady(budgetMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + budgetMs
        var gap = 700L
        while (System.currentTimeMillis() < deadline) {
            sync("wait")
            if (url.isNotBlank() && reachable) return true
            if (broken || lastStartBlockedReason != null) return false
            delay(gap.coerceAtMost(8000))
            gap = (gap * 2).coerceAtMost(8000)
        }
        return false
    }

    /**
     * 上游连接失败时立即失效当前地址：工作区可能在重启、地址会变，
     * 先清空避免后续请求继续打到旧地址，等重新同步拿到新地址。
     */
    fun invalidateEndpoint() {
        url = ""
        reachable = false
        lastHealthAt = 0
    }

    fun updateProxyKey(key: String) {
        if (account.proxyKey != key) account = account.copy(proxyKey = key)
    }

    fun state(): WorkState = when {
        broken -> WorkState.MISCONFIGURED
        starting || lifecycle == Lifecycle.PENDING ->
            if (creditExhausted) WorkState.NO_CREDIT else WorkState.STARTING
        creditExhausted -> WorkState.NO_CREDIT
        lifecycle == Lifecycle.RUNNING -> if (reachable) WorkState.READY else WorkState.UNREACHABLE
        lifecycle == Lifecycle.STOPPED -> WorkState.STOPPED
        lifecycle == Lifecycle.ABSENT -> WorkState.ABSENT
        else -> WorkState.UNKNOWN
    }

    fun snapshot(active: Boolean, coolingUntil: Long, coolingReason: String) = com.ikoude.cnb2api.data.AccountSnapshot(
        identity = identity,
        label = label,
        repo = account.normalizedRepo,
        org = account.normalizedRepo.substringBefore("/"),
        state = state(),
        url = url,
        host = com.ikoude.cnb2api.data.hostOf(url),
        workspaceId = workspaceId,
        branch = workspaceBranch.ifBlank { startBranch },
        runningSince = runningSince,
        lastError = lastError,
        lastCheckedAt = lastCheckedAt,
        active = active,
        usable = AccountPicker.canServe(this, settings().creditFloorPercent),
        lowCredit = (ledger?.credit?.remainPercent ?: 101.0) < settings().lowCreditPercent,
        belowFloor = AccountPicker.creditState(this, settings().creditFloorPercent).belowFloor,
        ledger = ledger,
        coolingUntil = coolingUntil,
        coolingReason = coolingReason,
        notice = QuotaMath.renderNotice(
            settings().lowCreditMessage,
            QuotaMath.noticeVars(label, ledger, settings().lowCreditPercent),
        ),
        canStart = !broken && lifecycle != Lifecycle.RUNNING && !starting,
    )

    companion object {
        fun fingerprint(token: String): String {
            if (token.isBlank()) return "none"
            val digest = MessageDigest.getInstance("SHA-256").digest(token.toByteArray())
            return digest.take(6).joinToString("") { "%02x".format(it) }
        }
    }
}
