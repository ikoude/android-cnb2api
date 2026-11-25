package com.ikoude.cnb2api.server

import android.content.Context
import android.net.ConnectivityManager
import android.system.OsConstants
import com.ikoude.cnb2api.core.AccountManager
import com.ikoude.cnb2api.core.Monitor
import com.ikoude.cnb2api.data.GatewayAddress
import com.ikoude.cnb2api.data.GatewayStats
import com.ikoude.cnb2api.data.LogLevel
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.NetworkInterface
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * 手机上的稳定 OpenAI 兼容网关：http://<手机 IP>:<端口>/v1。
 * 地址跟随、账号择优、额度冷却全在后面，客户端配置永不改变。
 */
class GatewayServer(
    private val manager: AccountManager,
    port: Int,
    private val listenHost: String = "0.0.0.0",
    private val context: Context? = null,
) : NanoHTTPD(listenHost, port) {

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        // 流式回答可能持续几分钟，因此限制「空闲」而不是总时长：
        // 连续 120 秒没有任何数据才断开，避免半句话处被整段切断。
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .callTimeout(0, TimeUnit.SECONDS)
        .build()

    @Volatile private var requests = 0L
    @Volatile private var refused = 0L
    @Volatile private var failures = 0L
    @Volatile private var failovers = 0L
    @Volatile private var startedAt = 0L

    private val hopHeaders = setOf(
        "host", "connection", "content-length", "transfer-encoding", "keep-alive",
        "proxy-authenticate", "proxy-authorization", "te", "trailer", "upgrade",
        "accept-encoding",
    )
    private val retryableCodes = setOf(401, 402, 403, 408, 425, 429, 500, 502, 503, 504, 522, 524)
    private val maxBodyBytes = 64L * 1024 * 1024
    // 预编译网卡判定正则，避免每次快照都对每个地址重新编译。
    private val unusableIfaceRegex = Regex("rmnet|ccmni|pdp|tun|ppp|dummy", RegexOption.IGNORE_CASE)
    private val mobileIfaceRegex = Regex("rmnet|ccmni|pdp", RegexOption.IGNORE_CASE)
    // 网卡枚举（ConnectivityManager + NetworkInterface）较重，缓存几秒，避免每秒巡检都打系统服务。
    @Volatile private var addrCache: List<IfAddr>? = null
    @Volatile private var addrCacheAt = 0L
    private val addrCacheTtl = 3_000L

    init {
        manager.statsProvider = { stats() }
    }

    fun stats(): GatewayStats {
        // 「所有地址」用双栈绑定（::），同时接受 IPv4 与 IPv6；两种地址都列出。
        val allMode = listenHost == "0.0.0.0" || listenHost == "::"
        val ifAddrs = if (allMode) cachedAddresses() else emptyList()
        val v4 = ifAddrs.filter { it.address is java.net.Inet4Address && !it.address.isLoopbackAddress }
        val v6 = ifAddrs.filter {
            it.address is java.net.Inet6Address && !it.address.isLoopbackAddress && !it.address.isLinkLocalAddress
        }
        // 「主」地址优先 Wi‑Fi/以太网等局域网地址；只有没有局域网 IPv6 时才回退到移动数据 IPv6。
        val lanV6 = v6.filter { !isMobileIface(it.name) }
        val p4 = v4.firstOrNull { it.active } ?: v4.firstOrNull()
        val p6 = lanV6.firstOrNull { it.active } ?: lanV6.firstOrNull()
            ?: v6.firstOrNull { it.active } ?: v6.firstOrNull()
        val entries = buildList {
            add(GatewayAddress("本机", "127.0.0.1", baseUrl("127.0.0.1"), true,
                note = "仅本机回环，手机自己访问", kind = "local"))
            if (allMode) {
                listOfNotNull(p4, p6).forEach { add(entryFor(it, true)) }
                // 其它 IPv4（热点/副网卡）和移动数据 IPv6 作为补充；移动 IPv4 仍不显示。
                v4.filter { a -> a.address.hostAddress != p4?.address?.hostAddress }
                    .forEach { add(entryFor(it, false)) }
                v6.filter { a ->
                    a.address.hostAddress != p6?.address?.hostAddress &&
                        isMobileIface(a.name)
                }.forEach { add(entryFor(it, false)) }
            }
        }
        return GatewayStats(
            listening = isAlive,
            port = listeningPort,
            localUrl = baseUrl("127.0.0.1"),
            ipv4 = v4.map { baseUrl(it.address.hostAddress ?: "") },
            ipv6 = listOfNotNull(p6).map { baseUrl(it.address.hostAddress ?: "") },
            entries = entries,
            listenHost = listenHost,
            key = manager.settings.gatewayKey,
            requests = requests,
            refused = refused,
            failures = failures,
            failovers = failovers,
            startedAt = startedAt,
        )
    }

    private data class IfAddr(
        val name: String,
        val address: java.net.InetAddress,
        val active: Boolean,
        val temporary: Boolean = false,
    )

    /** 命中缓存则直接返回，否则重新枚举；并发的重复枚举只会多算一次，结果一致。 */
    private fun cachedAddresses(): List<IfAddr> {
        val now = System.currentTimeMillis()
        val cached = addrCache
        if (cached != null && now - addrCacheAt < addrCacheTtl) return cached
        val fresh = activeAndOtherAddresses()
        addrCache = fresh
        addrCacheAt = now
        return fresh
    }

    /** 移动数据/VPN 地址对局域网客户端没有意义，不获取也不展示。 */
    private fun usableIface(name: String): Boolean = !unusableIfaceRegex.containsMatchIn(name)

    /** 系统当前活动网络的地址优先，其余网卡地址作为次要候选；按 LinkAddress flags 过滤临时 IPv6。 */
    private fun activeAndOtherAddresses(): List<IfAddr> {
        val out = mutableListOf<IfAddr>()
        val seen = mutableSetOf<String>()
        fun addIfAddr(iface: String, address: java.net.InetAddress, active: Boolean, temporary: Boolean) {
            val host = address.hostAddress ?: return
            if (address.isLoopbackAddress || address.isLinkLocalAddress || !seen.add(host)) return
            // 移动数据 IPv4 不可从局域网访问；IPv6 保留，但只保留非临时地址。
            if (address is java.net.Inet4Address && !usableIface(iface)) return
            if (address is java.net.Inet6Address && temporary) return
            out.add(IfAddr(iface, address, active, temporary))
        }
        try {
            val cm = context?.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            val activeNetwork = cm?.activeNetwork
            // 通过 getAllNetworks 的 LinkProperties 拿 flags；/proc/net/if_inet6 在 Android 16 被 SELinux 禁读。
            cm?.allNetworks?.forEach { network ->
                val props = cm.getLinkProperties(network) ?: return@forEach
                val iface = props.interfaceName ?: "当前网络"
                props.linkAddresses.forEach { la ->
                    val temp = (la.flags and OsConstants.IFA_F_TEMPORARY) != 0
                    addIfAddr(iface, la.address, network == activeNetwork, temp)
                }
            }
        } catch (e: Exception) { /* 无权限时退化为枚举全部网卡 */ }
        // 兜底：没有 LinkProperties 的虚拟/特殊网卡；这里拿不到临时标记，按非临时处理。
        try {
            val ifs = NetworkInterface.getNetworkInterfaces()
            while (ifs.hasMoreElements()) {
                val ni = ifs.nextElement()
                if (!ni.isUp || ni.isLoopback || ni.isVirtual) continue
                val list = ni.inetAddresses
                while (list.hasMoreElements()) {
                    addIfAddr(ni.name, list.nextElement(), false, false)
                }
            }
        } catch (e: Exception) { /* 忽略 */ }
        return out
    }

    private fun entryFor(a: IfAddr, primary: Boolean): GatewayAddress {
        val family = if (a.address is java.net.Inet4Address) "IPv4" else "IPv6"
        val mobile = isMobileIface(a.name)
        val base = when {
            mobile -> "移动数据"
            a.name.startsWith("wlan") -> "Wi‑Fi"
            a.name.startsWith("eth") -> "以太网"
            a.name.startsWith("wifi") -> "Wi‑Fi"
            else -> a.name
        }
        // 不显示 rmnet_data/ccmni 等底层网卡名；只告诉用户网络类型。
        val detail = if (a.active) "$base · 当前网络" else base
        val note = when {
            family == "IPv4" -> "Wi‑Fi/以太网 IPv4 · 同一局域网可访问"
            mobile -> "移动数据 IPv6 · 通常无法反连，但可以尝试"
            else -> "全局 IPv6 · 可访问性受网络环境影响"
        }
        return GatewayAddress(family, detail, baseUrl(a.address.hostAddress ?: ""), primary,
            note = note, kind = if (mobile) "mobile" else "lan")
    }

    private fun isMobileIface(name: String): Boolean = mobileIfaceRegex.containsMatchIn(name)

    fun baseUrl(address: String): String {
        val host = if (address.contains(':')) "[$address]" else address
        return "http://$host:${listeningPort}/v1"
    }

    fun startServer() {
        val loopback = listenHost == "127.0.0.1" || listenHost == "::1" || listenHost.equals("localhost", true)
        if (!loopback && manager.settings.gatewayKey.isBlank()) {
            throw IllegalStateException("监听局域网前必须先设置访问密钥")
        }
        start(30_000, true)
        startedAt = System.currentTimeMillis()
        manager.log("网关已监听 $listenHost:${listeningPort}/v1")
    }

    fun stopServer() {
        stop()
        manager.log("网关已停止")
    }

    override fun serve(session: IHTTPSession): Response {
        val method = session.method?.name ?: "GET"
        val uri = session.uri ?: "/"
        val peer = try { session.remoteIpAddress ?: "" } catch (e: Exception) { "" }
        if (uri.startsWith("/v1/")) manager.log("请求 $method $uri · $peer", LogLevel.INFO)

        if (method == "OPTIONS") {
            return NanoHTTPD.newFixedLengthResponse(Response.Status.NO_CONTENT, "text/plain", "")
                .apply { addCors(this) }
        }

        if (uri == "/health") {
            return json(Response.Status.OK, JSONObject().put("status", "ok"))
        }

        val key = manager.settings.gatewayKey
        if (key.isBlank() || !authorized(session, key)) {
            refused++
            manager.log("拒绝访问 · $peer", LogLevel.WARN)
            return json(Response.Status.UNAUTHORIZED, JSONObject()
                .put("ok", false)
                .put("reason", "缺少或错误的访问密钥 · 加 Authorization: Bearer <key>"))
        }

        if (uri == "/status") {
            return json(Response.Status.OK, statusJson())
        }

        if ((uri == "/v1/models" || uri == "/v1/models") && method == "GET") {
            return json(Response.Status.OK, modelsJson())
        }

        if (!uri.startsWith("/v1/")) {
            return json(Response.Status.NOT_FOUND, JSONObject().put("ok", false).put("reason", "not found"))
        }

        return forward(session, method, uri)
    }

    // ---------- 鉴权 ----------

    private fun authorized(session: IHTTPSession, key: String): Boolean {
        val bearer = header(session, "authorization")?.let { Regex("""(?i)^bearer\s+(.+)$""").find(it)?.groupValues?.get(1)?.trim() }
        val presented = bearer ?: header(session, "x-api-key")?.trim().orEmpty()
        if (presented.isBlank()) return false
        val md = MessageDigest.getInstance("SHA-256")
        return MessageDigest.isEqual(md.digest(presented.toByteArray()), md.digest(key.toByteArray()))
    }

    private fun header(session: IHTTPSession, name: String): String? =
        session.headers.entries.firstOrNull { it.key.equals(name, true) }?.value

    // ---------- 转发 ----------

    private fun forward(session: IHTTPSession, method: String, uri: String): Response {
        requests++
        val peer = try { session.remoteIpAddress ?: "" } catch (e: Exception) { "" }
        val wanted = header(session, "x-cnb2api-account").orEmpty()
        val primary = manager.pickUsable()
        val candidates = linkedSetOf<Monitor>()
        if (primary != null) candidates.add(primary)
        manager.fallbacks(primary).forEach { candidates.add(it) }
        if (wanted.isNotBlank()) {
            val named = candidates.filter { it.account.normalizedRepo == wanted || it.label == wanted }
            if (named.isEmpty()) {
                failures++
                return json(Response.Status.SERVICE_UNAVAILABLE, JSONObject()
                    .put("ok", false)
                    .put("reason", "指定的账号 $wanted 当前不可用（低于保底线、不可达或未运行）"))
            }
            candidates.clear(); candidates.addAll(named)
        }
        if (candidates.isEmpty()) {
            failures++
            manager.log("无可用账号 · $peer", LogLevel.WARN)
            return json(Response.Status.SERVICE_UNAVAILABLE, JSONObject()
                .put("ok", false)
                .put("reason", "没有可用账号 · 检查令牌、额度与工作区状态"))
        }

        val declared = header(session, "content-length")?.toLongOrNull() ?: 0L
        if (declared > maxBodyBytes) {
            failures++
            return json(statusOf(413), JSONObject()
                .put("ok", false)
                .put("reason", "请求体超过 ${maxBodyBytes / 1024 / 1024}MiB 上限"))
        }
        val body = try {
            readBody(session)
        } catch (e: Exception) {
            failures++
            manager.log("读取请求体失败 · ${e.message}", LogLevel.WARN)
            return json(statusOf(413), JSONObject()
                .put("ok", false)
                .put("reason", "请求体读取失败或超过 ${maxBodyBytes / 1024 / 1024}MiB 上限"))
        }
        val contentType = header(session, "content-type") ?: "application/json"
        val ordered = candidates.sortedByDescending { manager.cooldownOf(it).first == 0L }
        var lastMessage = "没有可用账号"
        var lastStatus = 503
        var lastDetail = ""
        var attempts = 0

        for (m in ordered) {
            attempts++
            val upstreamUrl = m.url
            if (upstreamUrl.isBlank()) {
                // 不再阻塞请求线程做完整同步：触发后台同步，本次跳过该账号，等下一轮巡检填好地址。
                manager.scope.launch { m.sync("gateway") }
                lastMessage = m.lastError ?: "网关地址尚未就绪"
                continue
            }

            val builder = Request.Builder().url("$upstreamUrl$uri")
            for ((k, v) in session.headers) {
                val lower = k.lowercase()
                if (lower in hopHeaders || lower == "authorization" || lower == "x-api-key" || lower == "x-cnb2api-account") continue
                builder.header(k, v)
            }
            val proxyKey = m.account.proxyKey
            if (proxyKey.isNotBlank()) builder.header("Authorization", "Bearer $proxyKey")
            val rb = if (body.isEmpty() && (method == "GET" || method == "HEAD")) null
            else body.toRequestBody(contentType.toMediaTypeOrNull())
            builder.method(method, rb)

            val call = http.newCall(builder.build())
            val upstream = try {
                call.execute()
            } catch (e: Exception) {
                lastMessage = "连接上游失败 · ${e.message ?: "网络错误"}"
                manager.log("连接上游失败 (${m.label}) · ${e.message}", LogLevel.ERROR)
                manager.penalize(m, lastMessage)
                continue
            }

            val code = upstream.code
            val retryable = code in retryableCodes || code >= 500
            if (retryable || code == 400 || code == 422) {
                lastDetail = upstream.body?.string().orEmpty().let { safeText(it) }
                lastStatus = code
                lastMessage = "upstream $code"
                upstream.close()
                if (code == 400 || code == 422) {
                    failures++
                    return json(statusOf(code), JSONObject()
                        .put("ok", false)
                        .put("reason", "cnb2api 拒绝了这次请求 · ${lastDetail.ifBlank { lastMessage }}"))
                }
                manager.penalize(m, lastMessage + if (lastDetail.isNotBlank()) " · $lastDetail" else "")
                continue
            }

            manager.reward(m)
            val response = NanoHTTPD.newChunkedResponse(statusOf(code), upstream.body?.contentType()?.toString() ?: "application/json", ClosingStream(upstream))
            addCors(response)
            upstream.headers.forEach { (name, value) ->
                val lower = name.lowercase()
                // content-type 已由 newChunkedResponse 设置；CORS 我们自己统一加，
                // 避免上游重复头让浏览器/客户端误判响应。
                if (lower in hopHeaders || lower == "content-encoding" || lower == "content-length" ||
                    lower == "content-type" || lower.startsWith("access-control-")) return@forEach
                response.addHeader(name, value)
            }
            val credit = m.ledger?.credit
            if (credit != null) {
                response.addHeader("x-cnb2api-credit-remain", num(credit.remain))
                response.addHeader("x-cnb2api-credit-total", num(credit.total))
                val low = (credit.remainPercent ?: 101.0) < manager.settings.lowCreditPercent
                if (low) response.addHeader("x-cnb2api-credit-low", "1")
                val notice = com.ikoude.cnb2api.core.QuotaMath.renderNotice(
                    manager.settings.lowCreditMessage,
                    com.ikoude.cnb2api.core.QuotaMath.noticeVars(m.label, m.ledger, manager.settings.lowCreditPercent),
                )
                if (low && notice.isNotBlank()) {
                    response.addHeader("x-cnb2api-credit-notice", java.net.URLEncoder.encode(notice, "UTF-8"))
                }
            }
            if (attempts > 1) failovers++
            return response
        }

        failures++
        manager.log("转发失败 $uri · $lastMessage", LogLevel.ERROR)
        return json(if (lastStatus in 400..499 && lastStatus != 401 && lastStatus != 402 && lastStatus != 403) statusOf(lastStatus) else Response.Status.SERVICE_UNAVAILABLE,
            JSONObject()
                .put("ok", false)
                .put("reason", "cnb2api 不可用 · $lastMessage")
                .put("upstream", lastDetail)
                .put("hint", "检查令牌权限 account-engage:r 与 repo-cnb-trigger:r，或补足额度"))
    }

    private fun readBody(session: IHTTPSession): ByteArray {
        val transfer = header(session, "transfer-encoding")?.lowercase().orEmpty()
        if (transfer.contains("chunked")) return readChunked(session.inputStream)
        val len = header(session, "content-length")?.toLongOrNull() ?: 0L
        if (len <= 0) return ByteArray(0)
        if (len > maxBodyBytes) throw IllegalArgumentException("超过 ${maxBodyBytes / 1024 / 1024}MiB 上限")
        val out = ByteArray(len.toInt())
        var off = 0
        while (off < out.size) {
            val n = session.inputStream.read(out, off, out.size - off)
            if (n < 0) break
            off += n
        }
        return if (off == out.size) out else out.copyOf(off)
    }

    /** 支持 OpenAI 客户端上传图片/大 JSON 时常用的 chunked 请求体。 */
    private fun readChunked(input: InputStream): ByteArray {
        val out = ByteArrayOutputStream()
        while (true) {
            val sizeLine = readAsciiLine(input)
            if (sizeLine.isBlank()) break
            val size = sizeLine.substringBefore(';').trim().toLongOrNull(16)
                ?: throw IllegalArgumentException("chunk size 无效")
            if (size == 0L) {
                // 吃掉 trailer 直到空行
                while (true) {
                    val trailer = readAsciiLine(input)
                    if (trailer.isBlank()) break
                }
                break
            }
            if (out.size().toLong() + size > maxBodyBytes) throw IllegalArgumentException("chunked 请求体超过上限")
            val chunk = ByteArray(size.toInt())
            var off = 0
            while (off < chunk.size) {
                val n = input.read(chunk, off, chunk.size - off)
                if (n < 0) break
                off += n
            }
            out.write(chunk, 0, off)
            // 每个 chunk 后面必须跟 CRLF；遇到 EOF 或其它字节说明请求体被截断/畸形。
            val cr = input.read()
            val lf = input.read()
            if (cr != '\r'.code || lf != '\n'.code) throw IllegalArgumentException("chunk 结束符无效")
        }
        return out.toByteArray()
    }

    private fun readAsciiLine(input: InputStream): String {
        val sb = StringBuilder()
        while (true) {
            val b = input.read()
            if (b < 0) break
            if (b == '\n'.code) break
            if (b != '\r'.code) sb.append(b.toChar())
        }
        return sb.toString()
    }

    private class ClosingStream(private val response: okhttp3.Response) : InputStream() {
        private val stream = response.body!!.byteStream()
        override fun read(): Int = stream.read()
        override fun read(b: ByteArray, off: Int, len: Int): Int = stream.read(b, off, len)
        override fun close() {
            try { stream.close() } finally { response.close() }
        }
    }

    // ---------- 本地 JSON ----------

    private fun statusJson(): JSONObject {
        val snap = manager.snapshot()
        val now = System.currentTimeMillis()
        val arr = JSONArray()
        snap.accounts.forEach { a ->
            arr.put(JSONObject()
                .put("label", a.label).put("repo", a.repo).put("org", a.org)
                .put("state", a.state.name.lowercase()).put("badge", a.state.badge)
                .put("host", a.host).put("workspaceId", a.workspaceId).put("branch", a.branch)
                .put("active", a.active).put("usable", a.usable).put("lowCredit", a.lowCredit)
                .put("belowFloor", a.belowFloor).put("lastError", a.lastError ?: JSONObject.NULL)
                .put("coolingMs", if (a.coolingUntil > now) a.coolingUntil - now else 0L)
                .put("credit", a.ledger?.credit?.let { c ->
                    JSONObject().put("used", num(c.used)).put("total", num(c.total))
                        .put("frozen", num(c.frozen)).put("remain", num(c.remain))
                        .put("percent", c.usedPercent?.let { num(it) } ?: JSONObject.NULL)
                } ?: JSONObject.NULL)
                .put("coreHours", a.ledger?.coreHours?.let { c ->
                    JSONObject().put("used", num(c.used)).put("total", num(c.total))
                        .put("remain", num(c.remain))
                } ?: JSONObject.NULL))
        }
        return JSONObject()
            .put("configured", snap.accounts.isNotEmpty())
            .put("accounts", arr)
            .put("gateway", JSONObject()
                .put("listening", snap.stats.listening).put("port", snap.stats.port)
                .put("baseUrl", snap.stats.localUrl)
                .put("local", snap.stats.localUrl)
                .put("ipv4", JSONArray(snap.stats.ipv4))
                .put("ipv6", JSONArray(snap.stats.ipv6))
                .put("entries", JSONArray().apply {
                    snap.stats.entries.forEach { e ->
                        put(
                            JSONObject().put("family", e.family).put("detail", e.detail)
                                .put("url", e.url).put("primary", e.primary)
                                .put("note", e.note).put("kind", e.kind),
                        )
                    }
                })
                .put("listenHost", snap.stats.listenHost)
                .put("key", snap.stats.key)
                .put("requests", snap.stats.requests).put("refused", snap.stats.refused)
                .put("failures", snap.stats.failures).put("failovers", snap.stats.failovers))
            .put("models", JSONArray(manager.settings.modelList))
            .put("logs", JSONArray().apply {
                snap.logs.takeLast(30).forEach { l ->
                    put(JSONObject().put("time", l.time).put("text", l.text).put("level", l.level.name.lowercase()))
                }
            })
    }

    private fun modelsJson(): JSONObject {
        val data = JSONArray()
        manager.settings.modelList.forEach {
            data.put(JSONObject().put("id", it).put("object", "model").put("owned_by", "cnb2api"))
        }
        return JSONObject().put("object", "list").put("data", data)
    }

    private fun json(status: NanoHTTPD.Response.IStatus, body: JSONObject): Response {
        val text = body.toString()
        val r = NanoHTTPD.newFixedLengthResponse(status, "application/json; charset=utf-8", text)
        addCors(r)
        return r
    }

    private fun addCors(r: Response?) {
        r?.addHeader("Access-Control-Allow-Origin", "*")
        r?.addHeader("Access-Control-Allow-Headers", "authorization, content-type, x-api-key, x-cnb2api-account")
        r?.addHeader("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
    }

    private class CodeStatus(private val codeValue: Int, private val desc: String) : NanoHTTPD.Response.IStatus {
        override fun getDescription(): String = desc
        override fun getRequestStatus(): Int = codeValue
    }

    /** NanoHTTPD 2.3.1 的 Status 枚举缺 402/502/504，用自定义 IStatus 透传状态码。
     *  注意 description 必须是标准原因短语（如 OK），否则状态行会变成
     *  `HTTP/1.1 HTTP 200`，严格客户端会判定为传输层错误。 */
    private fun statusOf(code: Int): NanoHTTPD.Response.IStatus = CodeStatus(code, "$code ${reasonOf(code)}")

    private fun reasonOf(code: Int): String = when (code) {
        200 -> "OK"; 201 -> "Created"; 202 -> "Accepted"; 204 -> "No Content"
        301 -> "Moved Permanently"; 302 -> "Found"; 304 -> "Not Modified"
        400 -> "Bad Request"; 401 -> "Unauthorized"; 402 -> "Payment Required"
        403 -> "Forbidden"; 404 -> "Not Found"; 405 -> "Method Not Allowed"
        408 -> "Request Timeout"; 409 -> "Conflict"; 413 -> "Payload Too Large"
        422 -> "Unprocessable Entity"; 429 -> "Too Many Requests"
        500 -> "Internal Server Error"; 501 -> "Not Implemented"; 502 -> "Bad Gateway"
        503 -> "Service Unavailable"; 504 -> "Gateway Timeout"
        else -> if (code in 400..499) "Client Error" else if (code in 500..599) "Server Error" else "OK"
    }

    private fun safeText(text: String): String {
        val t = text.trim()
        if (t.isEmpty()) return ""
        return try {
            val o = JSONObject(t)
            (o.optJSONObject("error")?.optString("message", "") ?: o.optString("message", ""))
                .ifBlank { t }.take(300)
        } catch (e: Exception) { t.take(300) }
    }

    private fun num(v: Double?): String = com.ikoude.cnb2api.data.num1(v)

}
