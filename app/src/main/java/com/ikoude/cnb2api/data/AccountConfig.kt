package com.ikoude.cnb2api.data

/** 一个 CNB 账号。身份 = 令牌指纹 + 仓库；PROXY_KEY 按仓库单独保存。 */
data class AccountConfig(
    val token: String = "",
    val repo: String = "",
    val proxyKey: String = "",
    val branch: String = "",
    val port: Int = 0,
    /** 本次观察到工作区进入运行状态的时刻；0 表示未运行。 */
    val runStartedAt: Long = 0L,
) {
    val normalizedRepo: String get() = normalizeRepo(repo)
    val org: String get() = normalizedRepo.substringBefore("/")
    val valid: Boolean get() = token.isNotBlank() && normalizedRepo.contains("/") && !token.startsWith("sk-")
}

/** 全局设置。对应 DSH 插件 Config 的全局项。 */
data class AppSettings(
    val models: String = "deepseek-v4.1-flash",
    val checkSeconds: Int = 120,
    val autoStart: Boolean = true,
    val branch: String = "main",
    val port: Int = 9001,
    val gatewayPort: Int = 41729,
    val gatewayKey: String = "",
    val lowCreditPercent: Int = 5,
    val creditFloorPercent: Int = 1,
    val lowCreditMessage: String = "",
    val accent: String = "blue",
    val appearance: String = "system",
    val listenHost: String = "0.0.0.0",
    val gatewayAutoStart: Boolean = true,
    val tabOrder: String = "dashboard,accounts,settings,logs,about",
    val hiddenTabs: String = "",
    val webdavUrl: String = "",
    val webdavUser: String = "",
    val webdavPassword: String = "",
    val autoBackup: Boolean = false,
    val backupPassword: String = "",
) {
    val modelList: List<String>
        get() = models.split(",").map { it.trim() }.filter { it.isNotEmpty() }
            .ifEmpty { listOf("deepseek-v4.1-flash") }
    val checkSecondsSafe: Int get() = checkSeconds.coerceAtLeast(30)
}

/** 与 lib/address.mjs 的 parseRepo 同口径。 */
fun normalizeRepo(input: String): String {
    var s = input.trim()
    if (s.isEmpty()) return ""
    s = s.replace(Regex("""^[a-zA-Z][a-zA-Z0-9+.-]*://"""), "")
    s = s.replace(Regex("""^git@[^:]+:"""), "")
    s = s.replace(Regex("""^[^/]+\.[a-zA-Z]{2,}/"""), "")
    s = s.trimStart('/')
    val m = Regex("""^([^/]+)/([^/?#]+)""").find(s)
    if (m == null) {
        val bare = s.replace(Regex("""\.git$"""), "").replace(Regex("""[/?#].*$"""), "")
        return if (Regex("""^[\w.-]+$""").matches(bare)) bare else ""
    }
    val org = m.groupValues[1].replace(Regex("""\.git$"""), "")
    val repo = m.groupValues[2].replace(Regex("""\.git$"""), "")
    return if (org.isNotEmpty() && repo.isNotEmpty()) "$org/$repo" else ""
}

/** 工作区记录归属仓库：只取 org/repo 两段，repo_url 可能带尾巴。 */
fun repoOfRecord(slug: String, repoUrl: String): String {
    val s = slug.trim('/')
    if (s.isNotBlank()) return s
    return repoUrl.replace(Regex("""^https?://[^/]+/"""), "")
        .replace(Regex("""\.git$"""), "")
        .split('/').filter { it.isNotBlank() }.take(2).joinToString("/")
}
