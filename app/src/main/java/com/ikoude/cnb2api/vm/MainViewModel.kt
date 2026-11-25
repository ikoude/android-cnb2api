package com.ikoude.cnb2api.vm

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ikoude.cnb2api.CnbApp
import com.ikoude.cnb2api.core.AccountManager
import com.ikoude.cnb2api.data.AccountConfig
import com.ikoude.cnb2api.data.AppSettings
import com.ikoude.cnb2api.data.AppSnapshot
import com.ikoude.cnb2api.data.Backup
import com.ikoude.cnb2api.data.OrgOption
import com.ikoude.cnb2api.net.CnbClient
import com.ikoude.cnb2api.net.WebDav
import com.ikoude.cnb2api.server.GatewayService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val cnbApp = app as CnbApp
    val manager: AccountManager = cnbApp.manager
    private val cnb = CnbClient()

    private val _snapshot = MutableStateFlow(manager.snapshot())
    val snapshot: StateFlow<AppSnapshot> = _snapshot.asStateFlow()

    /**
     * 设置单独成流：AppShell 只关心页签顺序/显隐与主题，不应被账号、日志或统计变化牵连重组。
     */
    private val _settings = MutableStateFlow(manager.settings)
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    private val _notice = MutableStateFlow("")
    val notice: StateFlow<String> = _notice.asStateFlow()

    /** 主题即时预览：设置页选择外观/主题色时先套用，保存后再落盘。 */
    private val _themeOverride = MutableStateFlow<Pair<String, String>?>(null)
    val themeOverride: StateFlow<Pair<String, String>?> = _themeOverride.asStateFlow()

    /** 导航顺序/显隐即时预览：设置页一改就生效，保存后再落盘。 */
    private val _tabsOverride = MutableStateFlow<Pair<String, String>?>(null)
    val tabsOverride: StateFlow<Pair<String, String>?> = _tabsOverride.asStateFlow()

    init {
        // 快照含网卡枚举等系统调用，放到后台线程计算；StateFlow 赋值线程安全，采集回到主线程。
        viewModelScope.launch(Dispatchers.Default) {
            while (isActive) {
                val s = manager.snapshot()
                _snapshot.value = s
                delay(1000)
            }
        }
    }

    fun previewTheme(appearance: String, accent: String) { _themeOverride.value = appearance to accent }

    fun previewTabs(order: String, hidden: String) { _tabsOverride.value = order to hidden }

    /** 仪表盘点击账号行：跳到账号页并展开对应卡片。 */
    private val _focusAccount = MutableStateFlow<String?>(null)
    val focusAccount: StateFlow<String?> = _focusAccount.asStateFlow()
    fun focusAccount(id: String) { _focusAccount.value = id }
    fun consumeFocusAccount() { _focusAccount.value = null }

    fun notify(text: String) { _notice.value = text }
    fun clearNotice() { _notice.value = "" }

    fun saveAccounts(list: List<AccountConfig>) {
        manager.configure(list, manager.settings)
        notify("已保存 ${list.size} 个账号")
    }

    private var gatewayRestartJob: Job? = null

    fun saveSettings(next: AppSettings) {
        val old = manager.settings
        manager.updateSettingsOnly(next)
        _settings.value = next
        _themeOverride.value = null
        _tabsOverride.value = null
        if (old.gatewayPort != next.gatewayPort || old.listenHost != next.listenHost) {
            scheduleGatewayRestart()
        }
        scheduleAutoBackup(next)
    }

    /**
     * 端口/监听地址改动后重启网关。防抖 800ms，避免输入框每敲一个字符就起停一次；
     * 且只在网关当前确实在运行时重启——用户主动停掉网关后改设置不会被重新拉起。
     */
    private fun scheduleGatewayRestart() {
        gatewayRestartJob?.cancel()
        gatewayRestartJob = viewModelScope.launch {
            delay(800)
            if (cnbApp.server != null) GatewayService.restart(getApplication())
        }
    }

    private var autoBackupJob: Job? = null

    /** 保存后自动上传：防抖 1.5s，避免设置页每敲一个字就传一次。 */
    private fun scheduleAutoBackup(s: AppSettings) {
        autoBackupJob?.cancel()
        if (!s.autoBackup || s.webdavUrl.isBlank()) return
        // 不允许把含令牌的备份以明文静默上传到网盘；自动上传必须设备份口令。
        if (s.backupPassword.isBlank()) return
        autoBackupJob = viewModelScope.launch {
            delay(1500)
            runCatching {
                val doc = Backup.export("all", manager.accounts(), manager.settings, s.backupPassword)
                WebDav.put(manager.settings.webdavUrl, manager.settings.webdavUser, manager.settings.webdavPassword, doc)
            }.onSuccess {
                notify("已自动上传备份")
            }.onFailure {
                notify("自动备份失败 · ${it.message}")
            }
        }
    }

    suspend fun uploadWebDav(content: String): Pair<String, Int> {
        val s = manager.settings
        return WebDav.put(s.webdavUrl, s.webdavUser, s.webdavPassword, content)
    }

    suspend fun downloadWebDav(): String {
        val s = manager.settings
        return WebDav.get(s.webdavUrl, s.webdavUser, s.webdavPassword).first
    }

    fun startService() {
        gatewayRestartJob?.cancel()
        GatewayService.start(getApplication())
        notify("网关服务已启动")
    }

    fun stopService() {
        gatewayRestartJob?.cancel()
        GatewayService.stop(getApplication())
        notify("网关服务已停止")
    }

    fun startAccount(id: String) {
        manager.startAccount(id)
        notify("已提交启动，等待 30–90 秒")
    }

    fun refreshAccount(id: String) = manager.refreshAccount(id)
    fun refreshAll() = manager.refreshAll()

    suspend fun probeToken(token: String): Result<List<OrgOption>> = try {
        val orgs = cnb.listOrgDetails(token)
        if (orgs.isEmpty()) Result.failure(Exception("令牌可用，但没有读到组织 · 检查 account-engage:r"))
        else Result.success(orgs)
    } catch (e: Exception) {
        Result.failure(e)
    }

    suspend fun probeRepos(token: String, org: String): List<Pair<String, Boolean>> =
        cnb.listOrgRepos(token, org)

    suspend fun verifyProxyKey(token: String, repo: String, key: String): String? =
        cnb.verifyProxyKey(token, repo, key, manager.settings.port)
}
