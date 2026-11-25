package com.ikoude.cnb2api.data

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.json.JSONArray
import org.json.JSONObject
import java.security.SecureRandom
import java.util.Base64

/** 多账号 + 设置存在 Keystore 加密的 SharedPreferences 里，不进备份、不写日志。 */
class SecureStore(context: Context) {

    private val prefs = try {
        val master = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context, "cnb2api_credentials", master,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    } catch (e: Exception) {
        context.getSharedPreferences("cnb2api_credentials_plain", Context.MODE_PRIVATE)
    }

    private fun newGatewayKey(): String =
        "sk-" + Base64.getUrlEncoder().withoutPadding()
            .encodeToString(ByteArray(24).also { SecureRandom().nextBytes(it) })

    fun loadAccounts(): List<AccountConfig> {
        val raw = prefs.getString("accounts", "[]") ?: "[]"
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                AccountConfig(
                    token = o.optString("token", ""),
                    repo = o.optString("repo", ""),
                    proxyKey = o.optString("proxyKey", ""),
                    branch = o.optString("branch", ""),
                    port = o.optInt("port", 0),
                    runStartedAt = o.optLong("runStartedAt", 0L),
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun saveAccounts(list: List<AccountConfig>) {
        val arr = JSONArray()
        list.forEach {
            arr.put(
                JSONObject()
                    .put("token", it.token.trim())
                    .put("repo", it.repo.trim())
                    .put("proxyKey", it.proxyKey.trim())
                    .put("branch", it.branch.trim())
                    .put("port", it.port)
                    .put("runStartedAt", it.runStartedAt),
            )
        }
        prefs.edit().putString("accounts", arr.toString()).apply()
    }

    fun loadSettings(): AppSettings {
        // 首次安装生成随机访问密钥，绝不在发行包里内置固定 key。
        var gatewayKey = prefs.getString("gatewayKey", "") ?: ""
        if (gatewayKey.isBlank()) {
            gatewayKey = newGatewayKey()
            prefs.edit().putString("gatewayKey", gatewayKey).apply()
        }
        return AppSettings(
        models = prefs.getString("models", "deepseek-v4.1-flash") ?: "deepseek-v4.1-flash",
        checkSeconds = prefs.getInt("checkSeconds", 120),
        autoStart = prefs.getBoolean("autoStart", true),
        branch = prefs.getString("branch", "main") ?: "main",
        port = prefs.getInt("port", 9001),
        gatewayPort = prefs.getInt("gatewayPort", 41729),
        gatewayKey = gatewayKey,
        lowCreditPercent = prefs.getInt("lowCreditPercent", 5),
        creditFloorPercent = prefs.getInt("creditFloorPercent", 1),
        lowCreditMessage = prefs.getString("lowCreditMessage", "") ?: "",
        accent = prefs.getString("accent", "blue") ?: "blue",
        appearance = prefs.getString("appearance", "system") ?: "system",
        listenHost = prefs.getString("listenHost", "0.0.0.0") ?: "0.0.0.0",
        gatewayAutoStart = prefs.getBoolean("gatewayAutoStart", true),
        tabOrder = prefs.getString("tabOrder", "dashboard,accounts,settings,logs") ?: "dashboard,accounts,settings,logs",
        hiddenTabs = prefs.getString("hiddenTabs", "") ?: "",
        webdavUrl = prefs.getString("webdavUrl", "") ?: "",
        webdavUser = prefs.getString("webdavUser", "") ?: "",
        webdavPassword = prefs.getString("webdavPassword", "") ?: "",
        autoBackup = prefs.getBoolean("autoBackup", false),
        backupPassword = prefs.getString("backupPassword", "") ?: "",
        )
    }

    fun saveSettings(s: AppSettings) {
        prefs.edit()
            .putString("models", s.models.trim().ifBlank { "deepseek-v4.1-flash" })
            .putInt("checkSeconds", s.checkSecondsSafe)
            .putBoolean("autoStart", s.autoStart)
            .putString("branch", s.branch.trim().ifBlank { "main" })
            .putInt("port", s.port.coerceIn(1, 65535))
            .putInt("gatewayPort", s.gatewayPort.coerceIn(1, 65535))
            .putString("gatewayKey", s.gatewayKey.trim().ifBlank { newGatewayKey() })
            .putInt("lowCreditPercent", s.lowCreditPercent.coerceIn(0, 100))
            .putInt("creditFloorPercent", s.creditFloorPercent.coerceIn(0, 100))
            .putString("lowCreditMessage", s.lowCreditMessage)
            .putString("accent", s.accent)
            .putString("appearance", s.appearance)
            .putString("listenHost", s.listenHost)
            .putBoolean("gatewayAutoStart", s.gatewayAutoStart)
            .putString("tabOrder", s.tabOrder)
            .putString("hiddenTabs", s.hiddenTabs)
            .putString("webdavUrl", s.webdavUrl.trim())
            .putString("webdavUser", s.webdavUser)
            .putString("webdavPassword", s.webdavPassword)
            .putBoolean("autoBackup", s.autoBackup)
            .putString("backupPassword", s.backupPassword)
            .apply()
    }

    fun clear() = prefs.edit().clear().apply()
}
