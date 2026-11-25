package com.ikoude.cnb2api.net

import com.ikoude.cnb2api.data.Lifecycle
import com.ikoude.cnb2api.data.OrgOption
import com.ikoude.cnb2api.data.Ledger
import com.ikoude.cnb2api.data.repoOfRecord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.time.Instant
import java.util.concurrent.TimeUnit

class ApiException(
    message: String,
    val status: Int = 0,
    val scopes: List<String> = emptyList(),
) : Exception(message) {
    val permission: Boolean get() = scopes.isNotEmpty()
}

/**
 * CNB OpenAPI。三条实测口径与插件一致：
 *  /workspace/list 不带 slug 拉全量再本地筛；只有 404 才算仓库不存在；
 *  403 且带 Missing required scopes 才是权限问题。
 */
class CnbClient(private val apiBase: String = "https://api.cnb.cool") {

    private val jsonType = "application/json; charset=utf-8".toMediaType()
    private val http = OkHttpClient.Builder()
        .callTimeout(30, TimeUnit.SECONDS)
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private suspend fun get(path: String, token: String, timeoutMs: Long = 30_000): String =
        withContext(Dispatchers.IO) {
            val client = clientFor(timeoutMs)
            val req = Request.Builder().url(apiBase + path)
                .header("Authorization", "Bearer $token").header("Accept", "application/json").build()
            client.newCall(req).execute().use { res ->
                val text = res.body?.string().orEmpty()
                if (!res.isSuccessful) throw toException(res.code, path, text)
                text
            }
        }

    private suspend fun post(path: String, token: String, body: String, timeoutMs: Long): String =
        withContext(Dispatchers.IO) {
            val client = clientFor(timeoutMs)
            val req = Request.Builder().url(apiBase + path)
                .header("Authorization", "Bearer $token").header("Accept", "application/json")
                .post(body.toRequestBody(jsonType)).build()
            client.newCall(req).execute().use { res ->
                val text = res.body?.string().orEmpty()
                if (!res.isSuccessful) throw toException(res.code, path, text)
                text
            }
        }

    // 按超时档位缓存 client，避免每次请求都 newBuilder().build()。
    private val timedClients = java.util.concurrent.ConcurrentHashMap<Long, OkHttpClient>()

    private fun clientFor(ms: Long): OkHttpClient =
        if (ms == 30_000L) http
        else timedClients.getOrPut(ms) { http.newBuilder().callTimeout(ms, TimeUnit.MILLISECONDS).build() }

    // ---------- 错误 ----------

    private val scopeRegex = Regex("""Missing required scopes?:\s*([a-z0-9:,\s_-]+)""", RegexOption.IGNORE_CASE)

    private fun parseScopes(text: String): List<String> =
        scopeRegex.find(text)?.groupValues?.getOrNull(1)
            ?.split(Regex("""[,\s]+"""))?.filter { it.isNotBlank() } ?: emptyList()

    private fun toException(code: Int, path: String, text: String): ApiException {
        if (code == 403) {
            val scopes = parseScopes(text)
            if (scopes.isNotEmpty()) {
                val labels = mapOf(
                    "repo-cnb-trigger:r" to "读启动日志（唯一地址来源）",
                    "account-engage:r" to "列出工作区",
                    "repo-cnb-trigger:rw" to "启动工作区",
                    "group-resource:r" to "组织额度",
                    "repo-cnb-detail:r" to "工作区详情",
                )
                val parts = scopes.joinToString("；") { "$it（${labels[it] ?: "未知 scope"}）" }
                return ApiException("令牌缺少权限 · $parts · 在 CNB 头像 → 访问令牌中勾选", 403, scopes)
            }
            return ApiException("权限不足（403）· $path", 403)
        }
        val msg = when (code) {
            401 -> "令牌无效或已过期 · 在 CNB 头像 → 访问令牌 重新生成"
            404 -> "不存在或令牌无权访问"
            else -> "$path → HTTP $code${text.take(120).let { if (it.isBlank()) "" else " · $it" }}"
        }
        return ApiException(msg, code)
    }

    fun describe(error: Throwable): String = when (error) {
        is ApiException -> error.message ?: "CNB 接口错误"
        else -> error.message ?: "未知错误"
    }

    // ---------- 工作区 ----------

    suspend fun listWorkspaces(token: String, repo: String = ""): List<JSONObject> = withContext(Dispatchers.IO) {
        val all = mutableListOf<JSONObject>()
        val pageSize = 100
        for (page in 1..10) {
            val text = get("/workspace/list?page=$page&page_size=$pageSize", token)
            val body = runCatching { JSONObject(text) }.getOrNull() ?: break
            val arr = body.optJSONArray("list") ?: body.optJSONArray("data") ?: JSONArray()
            val rows = (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
            all.addAll(rows)
            if (rows.isEmpty()) break
            val more = if (body.has("hasMore")) body.optBoolean("hasMore")
            else if (body.has("has_more")) body.optBoolean("has_more")
            else rows.size == pageSize
            if (!more) break
            val total = body.optLong("total", -1)
            if (total > 0 && all.size >= total) break
        }
        if (repo.isBlank()) all.toList()
        else all.filter { repoOfRecord(it.optString("slug", ""), it.optString("repo_url", "")) == repo }
    }

    fun pickWorkspace(rows: List<JSONObject>): JSONObject? {
        if (rows.isEmpty()) return null
        val running = rows.filter { it.optString("status", "") == "running" }
        val pool = if (running.isNotEmpty()) running else rows
        return pool.maxByOrNull { tsOf(it) }
    }

    fun lifecycleOf(row: JSONObject?): Lifecycle = when (row?.optString("status", "")) {
        "running" -> Lifecycle.RUNNING
        "building", "pending", "starting" -> Lifecycle.PENDING
        null -> Lifecycle.ABSENT
        else -> Lifecycle.STOPPED
    }

    private fun tsOf(o: JSONObject): Long {
        for (k in listOf("create_time", "createTime", "created_at", "updated_at")) {
            val raw = o.opt(k) ?: continue
            val v = when (raw) {
                is Number -> raw.toLong()
                is String -> raw.toLongOrNull() ?: try { Instant.parse(raw).toEpochMilli() } catch (e: Exception) { 0L }
                else -> 0L
            }
            if (v > 0) return v
        }
        return 0L
    }

    suspend fun repoExists(token: String, repo: String): Boolean {
        if (!repo.contains("/")) return false
        return try {
            get("/$repo", token, 12_000)
            true
        } catch (e: ApiException) {
            e.status != 404
        } catch (e: Exception) {
            true
        }
    }

    suspend fun resolveUrl(token: String, repo: String, sn: String, port: Int): String? =
        urlFromDetail(token, repo, sn, port) ?: urlFromLog(token, repo, sn, port)

    private val uriRegex = Regex("""([a-z0-9][a-z0-9-]*)-(?:\{\{port\}\}|[0-9]+)\.cnb\.run(?![\w.-])""")
    private val templateRegex = Regex("""\{\{\s*port\s*\}\}""", RegexOption.IGNORE_CASE)
    private val ansiRegex = Regex("""\x1B\[[0-9;]*[A-Za-z]""")
    private val logUriRegex = Regex("""PROXY_URI\s*[:=]\s*["']?(https?://\S+)""", RegexOption.IGNORE_CASE)
    private val anyHostRegex = Regex("""([a-z0-9][a-z0-9-]*)-(?:\{\{port\}\}|\d+)\.cnb\.run(?![\w.-])""")

    private fun findGatewayUrl(text: String, port: Int): String? {
        val m = uriRegex.find(text.replace(ansiRegex, "")) ?: return null
        return "https://" + m.value.replace(templateRegex, port.toString())
    }

    suspend fun urlFromDetail(token: String, repo: String, sn: String, port: Int): String? {
        val text = try {
            get("/$repo/-/workspace/detail/${encode(sn)}", token, 12_000)
        } catch (e: Exception) {
            return null
        }
        val j = runCatching { JSONObject(text) }.getOrNull() ?: return null
        for (key in listOf("webide", "jumpUrl", "codebuddyweb", "remoteSsh")) {
            val u = findGatewayUrl(j.optString(key, ""), port)
            if (u != null) return u
        }
        return null
    }

    suspend fun urlFromLog(token: String, repo: String, sn: String, port: Int): String? {
        val text = get("/$repo/-/build/runner/download/log/${encode(sn)}-001", token, 30_000)
            .replace(ansiRegex, "")
        // 先认 PROXY_URI 的几种写法（= / :），取最后一条；日志可能被颜色码夹住，已先剥离。
        for (m in logUriRegex.findAll(text).toList().asReversed()) {
            val raw = m.groupValues[1].trim().trim('"', '\'', '<', '>', ')')
                .trimEnd('.', ',', ';', '/')
            val u = raw.replace(templateRegex, port.toString())
            if (uriRegex.containsMatchIn(u)) return u
        }
        // 兜底：仓库改成直接打印地址（没有 PROXY_URI 前缀/协议）时，取最后出现的网关主机。
        val hosts = anyHostRegex.findAll(text).toList()
        if (hosts.isNotEmpty()) {
            return "https://" + hosts.last().value.replace(templateRegex, port.toString())
        }
        return null
    }

    suspend fun healthy(base: String, timeoutMs: Long = 12_000): Boolean = withContext(Dispatchers.IO) {
        try {
            val client = http.newBuilder().callTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                .followRedirects(false).build()
            val req = Request.Builder().url("$base/health").build()
            client.newCall(req).execute().use { res ->
                if (res.code != 200) return@use false
                Regex("\"status\"\\s*:\\s*\"ok\"").containsMatchIn(res.body?.string().orEmpty())
            }
        } catch (e: Exception) { false }
    }

    suspend fun startWorkspace(token: String, repo: String, branch: String) {
        val body = JSONObject().put("branch", branch).toString()
        post("/$repo/-/workspace/start", token, body, 60_000)
    }

    // ---------- 额度 ----------

    private fun num(v: Any?): Double? = when (v) {
        null -> null
        is Number -> v.toDouble().takeIf { it.isFinite() }
        is JSONObject -> num(v.opt("total"))
        is String -> v.toDoubleOrNull()
        else -> null
    }

    private data class Bonus(val amount: Double, val label: String?, val expired: Boolean)

    private fun bonusOf(j: JSONObject?, key: String, labelKey: String = key): Bonus? {
        if (j == null) return null
        val amount = j.optDouble(key, 0.0)
        if (!amount.isFinite() || amount <= 0) return null
        val expireRaw = j.optString("${labelKey}_expire", "").ifBlank { null }
        val expired = expireRaw?.let {
            if (it.startsWith("9999")) false
            else {
                val t = try { Instant.parse(it).toEpochMilli() } catch (e: Exception) { 0L }
                t > 0 && t < System.currentTimeMillis()
            }
        } ?: false
        return Bonus(amount, j.optString("${labelKey}_desc", "").ifBlank { null }, expired)
    }

    private fun settle(totalRaw: Double?, usedRaw: Double?, frozenRaw: Double?, b: Bonus?, scale: Double): com.ikoude.cnb2api.data.Bucket? {
        if (totalRaw == null) return null
        val spent = usedRaw ?: 0.0
        val held = frozenRaw ?: 0.0
        val bonusRaw = (b?.amount ?: 0.0) * scale
        val baseRaw = (totalRaw - bonusRaw).coerceAtLeast(0.0)
        val remain = (totalRaw - spent - held).coerceAtLeast(0.0)
        val pct = if (totalRaw == 0.0 || usedRaw == null) null
        else ((spent + held) / totalRaw * 100).coerceIn(0.0, 100.0)
        return com.ikoude.cnb2api.data.Bucket(
            total = totalRaw / scale,
            used = usedRaw?.let { it / scale },
            frozen = held / scale,
            remain = remain / scale,
            base = baseRaw / scale,
            bonus = b?.amount,
            bonusLabel = b?.label,
            bonusExpired = b?.expired ?: false,
            usedPercent = pct,
        )
    }

    suspend fun readQuota(token: String, org: String, timeoutMs: Long = 20_000): Ledger = coroutineScope {
        val failures = mutableListOf<String>()
        suspend fun grab(name: String): JSONObject? {
            val text = try {
                get("/$org/-/charge/$name", token, timeoutMs)
            } catch (e: Exception) {
                synchronized(failures) { failures.add(describe(e)) }
                return null
            }
            return runCatching { JSONObject(text) }.getOrElse {
                synchronized(failures) { failures.add("$name 解析失败") }
                null
            }
        }
        val q = async { grab("quota") }
        val v = async { grab("volume") }
        val s = async { grab("special-amount") }
        val quota = q.await(); val volume = v.await(); val special = s.await()
        val credit = settle(
            num(quota?.opt("credit_in_milli")), num(volume?.opt("credit_in_milli")),
            num(volume?.opt("freeze_credit_in_milli")) ?: 0.0,
            bonusOf(special, "credit"), 1000.0,
        )
        val core = settle(
            num(quota?.opt("dev_in_sec")), num(volume?.opt("dev_in_sec")),
            num(volume?.opt("freeze_dev_in_sec")) ?: 0.0,
            bonusOf(special, "compute_develop_corehour", "compute_develop"), 3600.0,
        )
        Ledger(credit, core, failures)
    }

    // ---------- 登录辅助 ----------

    suspend fun whoAmI(token: String): String? = try {
        val j = JSONObject(get("/user", token, 12_000))
        j.optString("username", "").ifBlank { null }
    } catch (e: Exception) { null }

    suspend fun listRepos(token: String): List<String> = try {
        val text = get("/user/repos?page=1&page_size=100", token, 15_000)
        val arr = if (text.trimStart().startsWith("[")) JSONArray(text)
        else JSONObject(text).optJSONArray("list") ?: JSONArray()
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val path = o.optString("path", "").ifBlank {
                val owner = o.optString("owner", "")
                val name = o.optString("name", "")
                if (owner.isNotBlank() && name.isNotBlank()) "$owner/$name" else name
            }
            path.ifBlank { null }?.let { com.ikoude.cnb2api.data.normalizeRepo(it) }.takeUnless { it.isNullOrBlank() }
        }.distinct()
    } catch (e: Exception) { emptyList() }

    suspend fun listOrgs(token: String): List<String> {
        val out = linkedSetOf<String>()
        val repos = listRepos(token)
        for (repo in repos) repo.substringBefore("/").takeIf { it.isNotBlank() }?.let { out.add(it) }
        // 用户名只在它自己名下确有仓库时才作为命名空间列出（个人空间）。
        val username = whoAmI(token)
        if (username != null && repos.any { it.startsWith("$username/") }) out.add(username)
        try {
            val text = get("/user/groups?page=1&page_size=50", token, 12_000)
            val arr = if (text.trimStart().startsWith("[")) JSONArray(text)
            else JSONObject(text).optJSONArray("list") ?: JSONArray()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                (o.optString("path", "").ifBlank { o.optString("name", "") })
                    .takeIf { it.isNotBlank() }?.let { out.add(it) }
            }
        } catch (e: Exception) { /* 忽略 */ }
        return out.toList()
    }

    suspend fun listOrgDetails(token: String): List<OrgOption> = coroutineScope {
        val names = listOrgs(token)
        val me = whoAmI(token)
        names.map { name ->
            async {
                val credit = try { readQuota(token, name, 6_000).credit } catch (e: Exception) { null }
                OrgOption(name = name, personal = me != null && name == me, credit = credit)
            }
        }.map { it.await() }
    }

    /**
     * 验证 PROXY_KEY：先找到工作区网关，再用 /v1/models 试一次鉴权。
     * 返回 null 表示通过；否则是一句可直接展示的原因。
     */
    suspend fun verifyProxyKey(token: String, repo: String, proxyKey: String, port: Int): String? {
        val slug = com.ikoude.cnb2api.data.normalizeRepo(repo)
        if (!slug.contains("/")) return "仓库需要 组织/仓库"
        return try {
            val row = pickWorkspace(listWorkspaces(token, slug)) ?: return "该仓库还没有工作区，启动后再验证"
            val sn = row.optString("sn", "")
            if (sn.isBlank()) return "工作区编号缺失"
            val url = resolveUrl(token, slug, sn, port) ?: return "取不到工作区地址，稍后再验证"
            if (!healthy(url, 8_000)) return "工作区网关不可用，暂时无法验证密钥"
            withContext(Dispatchers.IO) {
                val req = Request.Builder().url("$url/v1/models")
                    .header("Authorization", "Bearer $proxyKey")
                    .header("Accept", "application/json").build()
                http.newBuilder().callTimeout(12, TimeUnit.SECONDS).build()
                    .newCall(req).execute().use { res ->
                        when {
                            res.isSuccessful -> null
                            res.code == 401 || res.code == 403 ->
                                "网关拒绝了这个密钥 · 核对仓库 .cnb.yml 里 env 的 PROXY_KEY"
                            else -> explainUpstreamError(res.code, res.body?.string().orEmpty())
                        }
                    }
            }
        } catch (e: Exception) {
            "无法验证密钥 · ${e.message ?: "网络错误"}"
        }
    }

    private fun explainUpstreamError(status: Int, text: String): String {
        val detail = try {
            val o = JSONObject(text)
            o.optString("message", "").ifBlank { o.optString("error", "").ifBlank { o.optString("msg", "") } }
        } catch (e: Exception) {
            text.replace(Regex("""\s+"""), " ").trim()
        }
        val hint = when {
            status == 402 -> "额度不足"
            status == 408 || status == 425 -> "网关超时"
            status == 429 -> "请求过于频繁，稍后再试"
            status >= 500 -> "网关内部错误"
            else -> "网关返回错误"
        }
        return "$hint · HTTP $status" + if (detail.isNotBlank()) " · ${detail.take(300)}" else ""
    }

    suspend fun listOrgRepos(token: String, org: String): List<Pair<String, Boolean>> {
        val rows = try { listWorkspaces(token) } catch (e: Exception) { emptyList() }
        val byRepo = linkedMapOf<String, Boolean>()
        for (row in rows) {
            val repo = repoOfRecord(row.optString("slug", ""), row.optString("repo_url", ""))
            if (!repo.startsWith("$org/")) continue
            val running = row.optString("status", "") == "running"
            if (!byRepo.containsKey(repo) || (running && !byRepo[repo]!!)) byRepo[repo] = running
        }
        for (repo in listRepos(token)) if (repo.startsWith("$org/") && !byRepo.containsKey(repo)) byRepo[repo] = false
        return byRepo.toList().sortedByDescending { it.second }
    }

    private fun encode(s: String): String = URLEncoder.encode(s, "UTF-8")
}
