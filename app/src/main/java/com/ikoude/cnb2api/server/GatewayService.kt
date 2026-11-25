package com.ikoude.cnb2api.server

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.ikoude.cnb2api.CnbApp
import com.ikoude.cnb2api.R

/** 常驻前台服务：让网关在切后台、锁屏后继续监听。 */
class GatewayService : Service() {

    private val app get() = application as CnbApp
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForegroundCompat()
        acquireLocks()
        app.startGateway()
    }

    /**
     * API 34+ 使用 specialUse：长期运行的网关不适合 dataSync（Android 15 对 dataSync
     * 有 6 小时/24 小时限制）。旧系统传 0，由 manifest 的 dataSync 类型兜底。
     */
    private fun startForegroundCompat() {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }
        ServiceCompat.startForeground(this, NOTI_ID, notification(), type)
    }

    /** 熄屏后保持 CPU 和 Wi‑Fi 活跃，避免网关 socket 被系统挂起。 */
    @SuppressLint("WakelockTimeout")
    private fun acquireLocks() {
        try {
            val pm = getSystemService(PowerManager::class.java)
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "cnb2api:gateway").apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (e: Exception) {
            app.manager.log("获取 WakeLock 失败 · ${e.message}")
        }
        try {
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            @Suppress("DEPRECATION")
            wifiLock = wm?.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "cnb2api:gateway-wifi")?.apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (e: Exception) {
            app.manager.log("获取 Wi‑Fi Lock 失败 · ${e.message}")
        }
    }

    private fun releaseLocks() {
        try { wakeLock?.takeIf { it.isHeld }?.release() } catch (_: Exception) {}
        try { wifiLock?.takeIf { it.isHeld }?.release() } catch (_: Exception) {}
        wakeLock = null
        wifiLock = null
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_RESTART) app.restartGateway()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        releaseLocks()
        app.stopGateway()
        super.onDestroy()
    }

    private fun createChannel() {
        val channel = NotificationChannel(CHANNEL_ID, "网关服务", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun notification() = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_stat_gateway)
        .setContentTitle("网关运行中")
        .setContentText("OpenAI 接口已就绪")
        .setOngoing(true)
        .setPriority(NotificationCompat.PRIORITY_LOW)
        .build()

    companion object {
        private const val CHANNEL_ID = "cnb2api-gateway"
        private const val NOTI_ID = 1001
        const val ACTION_RESTART = "com.ikoude.cnb2api.RESTART"

        fun start(context: Context) {
            val intent = Intent(context, GatewayService::class.java)
            androidx.core.content.ContextCompat.startForegroundService(context, intent)
        }

        fun restart(context: Context) {
            val intent = Intent(context, GatewayService::class.java).setAction(ACTION_RESTART)
            androidx.core.content.ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) = context.stopService(Intent(context, GatewayService::class.java))
    }
}
