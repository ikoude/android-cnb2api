package com.ikoude.cnb2api.data

enum class Lifecycle { ABSENT, PENDING, RUNNING, STOPPED }

enum class WorkState {
    READY, NO_CREDIT, UNREACHABLE, STOPPED, ABSENT, STARTING, MISCONFIGURED, UNKNOWN;

    val badge: String get() = when (this) {
        READY -> "就绪"
        NO_CREDIT -> "无额"
        UNREACHABLE -> "掉线"
        STOPPED -> "停止"
        ABSENT -> "未建"
        STARTING -> "启动"
        MISCONFIGURED -> "异常"
        UNKNOWN -> "识别"
    }
}

/** 一笔额度。usedPercent 用「已用 + 冻结」占总额算——冻结在那一刻已不可用。 */
data class Bucket(
    val total: Double,
    val used: Double?,
    val frozen: Double,
    val remain: Double,
    val base: Double,
    val bonus: Double?,
    val bonusLabel: String?,
    val bonusExpired: Boolean,
    val usedPercent: Double?,
) {
    val consumed: Double get() = (used ?: 0.0) + frozen
    val exhausted: Boolean get() = remain <= 0.0
    val remainPercent: Double? get() = if (total > 0) (remain / total * 100).coerceIn(0.0, 100.0) else null
}

enum class LogLevel { INFO, WARN, ERROR }

data class LogLine(val time: String, val text: String, val level: LogLevel)

data class OrgOption(val name: String, val personal: Boolean, val credit: Bucket?)

data class Ledger(
    val credit: Bucket? = null,
    val coreHours: Bucket? = null,
    val failures: List<String> = emptyList(),
)

/** 界面与 /status 共用的账号快照；字段与 DSH 插件 describe() 对齐。 */
data class AccountSnapshot(
    val identity: String,
    val label: String,
    val repo: String,
    val org: String,
    val state: WorkState,
    val url: String,
    val host: String,
    val workspaceId: String,
    val branch: String,
    val runningSince: Long,
    val lastError: String?,
    val lastCheckedAt: Long,
    val active: Boolean,
    val usable: Boolean,
    val lowCredit: Boolean,
    val belowFloor: Boolean,
    val ledger: Ledger?,
    /** 冷却结束的绝对时间戳（0 表示未冷却）；不在快照里放“剩余毫秒”，否则每秒都变会导致无谓重组。 */
    val coolingUntil: Long,
    val coolingReason: String,
    val notice: String,
    val canStart: Boolean,
) {
    val detail: String
        get() = buildList {
            if (host.isNotBlank()) add(maskHost(host))
            if (branch.isNotBlank()) add(branch)
            if (workspaceId.isNotBlank()) add("实例 $workspaceId")
            val uptime = formatUptime(runningSince)
            if (uptime.isNotBlank()) add("运行 $uptime")
        }.joinToString(" · ")
}

data class GatewayAddress(
    val family: String,
    val detail: String,
    val url: String,
    val primary: Boolean = false,
    /** 给用户看的可访问范围说明，例如「仅本机」「局域网可用」。 */
    val note: String = "",
    /** local / lan / mobile，用于界面分组和文案。 */
    val kind: String = "",
)

data class GatewayStats(
    val listening: Boolean = false,
    val port: Int = 0,
    val localUrl: String = "",
    val ipv4: List<String> = emptyList(),
    val ipv6: List<String> = emptyList(),
    val entries: List<GatewayAddress> = emptyList(),
    val listenHost: String = "0.0.0.0",
    val key: String = "",
    val requests: Long = 0,
    val refused: Long = 0,
    val failures: Long = 0,
    val failovers: Long = 0,
    val startedAt: Long = 0,
)

data class AppSnapshot(
    val accounts: List<AccountSnapshot> = emptyList(),
    val stats: GatewayStats = GatewayStats(),
    val settings: AppSettings = AppSettings(),
    val logs: List<LogLine> = emptyList(),
)

fun formatUptime(since: Long, now: Long = System.currentTimeMillis()): String {
    if (since <= 0) return ""
    val minutes = ((now - since) / 60000).coerceAtLeast(0)
    val hours = minutes / 60
    val days = hours / 24
    return when {
        days > 0 -> "${days}d${hours % 24}h"
        hours > 0 -> "${hours}h"
        else -> "${minutes}m"
    }
}

fun num1(v: Double?): String {
    if (v == null) return "?"
    val r = kotlin.math.round(v * 10) / 10
    return if (r == r.toLong().toDouble()) r.toLong().toString() else String.format(java.util.Locale.US, "%.1f", r)
}

fun maskHost(input: String): String {
    val host = input.substringAfter("://").substringBefore("/").substringBefore("?")
    if (host.isEmpty()) return ""
    val m = Regex("""^([^-]+)-(\d+\..+)$""").find(host)
    if (m != null) return "${m.groupValues[1].take(4)}****-${m.groupValues[2]}"
    if (host.length <= 10) return host
    return "${host.take(4)}****${host.takeLast(4)}"
}

fun hostOf(url: String): String = try {
    java.net.URI(url).host ?: url
} catch (e: Exception) {
    url
}
