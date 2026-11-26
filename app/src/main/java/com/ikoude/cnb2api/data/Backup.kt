package com.ikoude.cnb2api.data

import org.bouncycastle.crypto.generators.SCrypt
import org.json.JSONArray
import org.json.JSONObject
import java.security.SecureRandom
import java.time.Instant
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

data class BackupParseResult(val scope: String, val accounts: List<AccountConfig>, val settings: AppSettings?)
data class BackupMergeResult(val added: Int, val replaced: Int, val accounts: List<AccountConfig>)

/**
 * 与 DSH 插件 lib/backup.mjs 完全兼容的备份格式：
 * - 明文：{format,version,exportedAt,scope,encrypted:false,data:{accounts?,settings?}}
 * - 加密：额外 kdf{name:"scrypt",N,r,p,salt}、cipher{name:"aes-256-gcm",iv,tag}、
 *   data 为密文 base64（GCM tag 单独存放）。
 * 默认口令加密；口令留空导出明文。gatewayKey 永不进备份。
 */
object Backup {
    const val FORMAT = "cnb2api-backup"
    const val VERSION = 1
    private const val N = 16384
    private const val R = 8
    private const val P = 1
    private const val KEY_LEN = 32
    private const val SALT_BYTES = 16
    private const val IV_BYTES = 12
    private const val TAG_BYTES = 16

    private val base64Regex = Regex("""^[A-Za-z0-9+/]*={0,2}$""")
    private val scopes = setOf("accounts", "settings", "all")

    fun export(scope: String, accounts: List<AccountConfig>, settings: AppSettings, password: String): String {
        val wanted = if (scope in scopes) scope else "all"
        val payload = JSONObject()
        if (wanted == "accounts" || wanted == "all") {
            payload.put("accounts", JSONArray().apply {
                accounts.forEach { acc ->
                    if (acc.token.isNotBlank() && acc.repo.isNotBlank()) put(cleanAccount(acc))
                }
            })
        }
        if (wanted == "settings" || wanted == "all") {
            payload.put("settings", cleanSettings(settings))
        }
        val doc = JSONObject()
            .put("format", FORMAT).put("version", VERSION)
            .put("exportedAt", Instant.now().toString()).put("scope", wanted)
        if (password.isBlank()) {
            return doc.put("encrypted", false).put("data", payload).toString(2)
        }
        val salt = ByteArray(SALT_BYTES).also { SecureRandom().nextBytes(it) }
        val iv = ByteArray(IV_BYTES).also { SecureRandom().nextBytes(it) }
        val key = scrypt(password, salt, N, R, P)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        val full = cipher.doFinal(payload.toString().toByteArray(Charsets.UTF_8))
        val cipherText = full.copyOfRange(0, full.size - TAG_BYTES)
        val tag = full.copyOfRange(full.size - TAG_BYTES, full.size)
        return doc.put("encrypted", true)
            .put("kdf", JSONObject().put("name", "scrypt").put("N", N).put("r", R).put("p", P).put("salt", b64(salt)))
            .put("cipher", JSONObject().put("name", "aes-256-gcm").put("iv", b64(iv)).put("tag", b64(tag)))
            .put("data", b64(cipherText))
            .toString(2)
    }

    fun parse(input: String, password: String): BackupParseResult {
        val doc = try {
            JSONObject(input)
        } catch (e: Exception) {
            throw IllegalArgumentException("这不是备份文件 · 需要 cnb2api 导出的 JSON")
        }
        if (doc.optString("format") != FORMAT) throw IllegalArgumentException("这不是 cnb2api 的备份文件")
        if (!doc.has("version")) throw IllegalArgumentException("备份文件缺少版本号 · 不是有效的 cnb2api 备份")
        val version = doc.opt("version")?.toString()?.toDoubleOrNull()
            ?: throw IllegalArgumentException("备份版本号不是数字 · 文件可能已损坏")
        if (version > VERSION) throw IllegalArgumentException("备份版本 $version 比当前 App 新 · 请升级 App")

        val payload: JSONObject = if (doc.optBoolean("encrypted", false)) {
            if (password.isBlank()) throw IllegalArgumentException("这份备份已加密 · 需要口令")
            val kdf = doc.optJSONObject("kdf") ?: throw IllegalArgumentException("备份文件缺少 kdf")
            val cipherObj = doc.optJSONObject("cipher") ?: throw IllegalArgumentException("备份文件缺少 cipher")
            if (kdf.optString("name") != "scrypt") throw IllegalArgumentException("不支持的密钥派生算法 · ${kdf.optString("name")}")
            if (cipherObj.optString("name") != "aes-256-gcm") throw IllegalArgumentException("不支持的加密算法 · ${cipherObj.optString("name")}")
            val n = kdf.optInt("N", 0)
            val r = kdf.optInt("r", 0)
            val p = kdf.optInt("p", 0)
            if (n < 1024 || n > 65536 || r < 1 || r > 32 || p < 1 || p > 16) {
                throw IllegalArgumentException("备份文件损坏 · 加密参数超出允许范围")
            }
            val salt = decodeBase64(kdf.optString("salt", ""), SALT_BYTES, "salt")
            val iv = decodeBase64(cipherObj.optString("iv", ""), IV_BYTES, "iv")
            val tag = decodeBase64(cipherObj.optString("tag", ""), TAG_BYTES, "tag")
            val dataText = doc.optString("data", "")
            if (!base64Regex.matches(dataText) || dataText.length % 4 != 0) {
                throw IllegalArgumentException("备份文件损坏 · 密文不是合法 base64")
            }
            val body = Base64.getDecoder().decode(dataText)
            try {
                val key = scrypt(password, salt, n, r, p)
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
                val plain = cipher.doFinal(body + tag)
                JSONObject(String(plain, Charsets.UTF_8))
            } catch (e: Exception) {
                throw IllegalArgumentException("口令不对，或备份文件被损坏")
            }
        } else {
            doc.optJSONObject("data") ?: JSONObject()
        }

        val accounts = mutableListOf<AccountConfig>()
        payload.optJSONArray("accounts")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val acc = AccountConfig(
                    token = o.optString("token", ""),
                    repo = o.optString("repo", ""),
                    proxyKey = o.optString("proxyKey", ""),
                    branch = o.optString("branch", ""),
                    port = o.optInt("port", 0),
                )
                if (acc.token.isNotBlank() && acc.repo.isNotBlank()) accounts.add(acc)
            }
        }
        val settings = payload.optJSONObject("settings")?.let { parseSettings(it) }
        val scope = doc.optString("scope", "all").let { if (it in scopes) it else "all" }
        if (scope != "settings" && accounts.isEmpty()) throw IllegalArgumentException("备份里没有可用的账号")
        return BackupParseResult(scope, accounts, settings)
    }

    /** 与插件一致：按 repo 小写去重；同仓库以备份为准；现有但备份没有的保留。 */
    fun merge(existing: List<AccountConfig>, incoming: List<AccountConfig>): BackupMergeResult {
        val current = mutableListOf<AccountConfig>()
        val index = mutableMapOf<String, Int>()
        existing.forEach { row ->
            val key = row.normalizedRepo.lowercase()
            if (key.isNotBlank() && index.containsKey(key)) return@forEach
            if (key.isNotBlank()) index[key] = current.size
            // 去重只用小写 key；落盘必须保留原始 repo 大小写（CNB 仓库名可能区分大小写）。
            current.add(row)
        }
        var added = 0
        var replaced = 0
        incoming.forEach { row ->
            val key = row.normalizedRepo.lowercase()
            if (key.isBlank()) return@forEach
            // 仅用于去重的 key 小写；落盘 repo 必须保留原始大小写（CNB 仓库名可能区分大小写）。
            val normalized = row
            val at = index[key]
            if (at == null) {
                index[key] = current.size
                current.add(normalized)
                added++
            } else {
                current[at] = normalized
                replaced++
            }
        }
        return BackupMergeResult(added, replaced, current)
    }

    private fun cleanAccount(a: AccountConfig) = JSONObject().apply {
        put("token", a.token.trim())
        put("repo", a.normalizedRepo.trim())
        if (a.proxyKey.isNotBlank()) put("proxyKey", a.proxyKey.trim())
        if (a.branch.isNotBlank()) put("branch", a.branch.trim())
        if (a.port > 0) put("port", a.port)
    }

    private fun cleanSettings(s: AppSettings) = JSONObject().apply {
        put("checkSeconds", s.checkSeconds)
        put("autoStart", s.autoStart)
        put("locale", "auto")
        put("gatewayHost", if (s.listenHost == "::") "0.0.0.0" else s.listenHost)
        put("gatewayPort", s.gatewayPort)
        put("autoPublish", false)
        put("lowCreditPercent", s.lowCreditPercent)
        put("creditFloorPercent", s.creditFloorPercent)
        put("lowCreditMessage", s.lowCreditMessage)
        put("models", s.models)
        put("branch", s.branch)
        put("port", s.port)
        // App 扩展字段：插件会忽略，App 自己还原时保留。
        put("listenHost", s.listenHost)
        put("gatewayAutoStart", s.gatewayAutoStart)
        put("accent", s.accent)
        put("appearance", s.appearance)
        put("tabOrder", s.tabOrder)
        put("hiddenTabs", s.hiddenTabs)
    }

    private fun parseSettings(o: JSONObject): AppSettings {
        val hostRaw = o.optString("listenHost", o.optString("gatewayHost", "0.0.0.0"))
        val listenHost = when (hostRaw) {
            "0.0.0.0", "::" -> "::"
            "127.0.0.1", "localhost", "::1" -> "127.0.0.1"
            else -> "::"
        }
        return AppSettings(
            models = o.optString("models", "deepseek-v4.1-flash"),
            checkSeconds = o.optInt("checkSeconds", 120),
            autoStart = o.optBoolean("autoStart", true),
            branch = o.optString("branch", "main"),
            port = o.optInt("port", 9001),
            gatewayPort = o.optInt("gatewayPort", 41729),
            gatewayKey = "",
            lowCreditPercent = o.optInt("lowCreditPercent", 5),
            creditFloorPercent = o.optInt("creditFloorPercent", 1),
            lowCreditMessage = o.optString("lowCreditMessage", ""),
            accent = o.optString("accent", "blue"),
            appearance = o.optString("appearance", "system"),
            listenHost = listenHost,
            gatewayAutoStart = o.optBoolean("gatewayAutoStart", true),
            tabOrder = o.optString("tabOrder", "dashboard,accounts,settings,logs,about"),
            hiddenTabs = o.optString("hiddenTabs", ""),
        )
    }

    private fun scrypt(password: String, salt: ByteArray, n: Int, r: Int, p: Int): ByteArray =
        SCrypt.generate(password.toByteArray(Charsets.UTF_8), salt, n, r, p, KEY_LEN)

    private fun decodeBase64(value: String, bytes: Int, label: String): ByteArray {
        if (!base64Regex.matches(value) || value.length % 4 != 0) {
            throw IllegalArgumentException("备份文件损坏 · $label 不是合法 base64")
        }
        val buf = Base64.getDecoder().decode(value)
        if (buf.size != bytes) throw IllegalArgumentException("备份文件损坏 · $label 长度不对")
        return buf
    }

    private fun b64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)
}
