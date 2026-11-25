package com.ikoude.cnb2api

import android.app.Application
import com.ikoude.cnb2api.core.AccountManager
import com.ikoude.cnb2api.data.AccountConfig
import com.ikoude.cnb2api.data.SecureStore
import com.ikoude.cnb2api.server.GatewayServer
import org.json.JSONArray
import java.io.File

class CnbApp : Application() {

    lateinit var store: SecureStore
        private set
    lateinit var manager: AccountManager
        private set
    @Volatile var server: GatewayServer? = null
        private set

    override fun onCreate() {
        super.onCreate()
        store = SecureStore(this)
        manager = AccountManager(store)
        importAccountsOnce()
    }

    /** 首次启动且没有账号时，从 app 外部文件目录导入 accounts.json（便于从 DSH 配置迁移）。 */
    private fun importAccountsOnce() {
        if (store.loadAccounts().isNotEmpty()) return
        val file = File(getExternalFilesDir(null), "accounts.json")
        if (!file.exists()) return
        try {
            val arr = JSONArray(file.readText())
            val list = (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                AccountConfig(
                    token = o.optString("token", ""),
                    repo = o.optString("repo", ""),
                    proxyKey = o.optString("proxyKey", ""),
                    branch = o.optString("branch", ""),
                    port = o.optInt("port", 0),
                )
            }.filter { it.valid }
            if (list.isNotEmpty()) {
                store.saveAccounts(list)
                manager.configure(store.loadAccounts(), manager.settings)
                file.delete()
                manager.log("已从 accounts.json 导入 ${list.size} 个账号（已删除明文文件）")
            }
        } catch (e: Exception) {
            manager.log("导入 accounts.json 失败 · ${e.message}")
        }
    }

    @Synchronized
    fun startGateway() {
        if (server != null) return
        val port = manager.settings.gatewayPort
        val configured = manager.settings.listenHost
        // 0.0.0.0 与 :: 都表示「所有地址」，统一用双栈 :: 以便同时服务 IPv4/IPv6。
        val want = if (configured == "0.0.0.0") "::" else configured
        try {
            val s = GatewayServer(manager, port, want, this)
            s.startServer()
            server = s
            manager.startPolling()
        } catch (e: Exception) {
            manager.log("网关启动失败（$want）· ${e.message}")
            // 监听地址不可用时回退到本机，保证仍可用
            try {
                val fallback = GatewayServer(manager, port, "127.0.0.1", this)
                fallback.startServer()
                server = fallback
                manager.startPolling()
                manager.log("已回退到本机监听 127.0.0.1")
            } catch (e2: Exception) {
                manager.log("回退失败 · ${e2.message}")
            }
        }
    }

    @Synchronized
    fun stopGateway() {
        server?.stopServer()
        server = null
        manager.stopPolling()
    }

    @Synchronized
    fun restartGateway() {
        stopGateway()
        startGateway()
    }
}
