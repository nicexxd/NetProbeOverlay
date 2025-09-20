package com.example.netprobeoverlay.overlay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.example.netprobeoverlay.R
import com.example.netprobeoverlay.network.NetProbe
import com.example.netprobeoverlay.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class OverlayService : Service() {
    companion object {
        const val EXTRA_MODE = "mode"
        const val MODE_NOTIFY_ONLY = "notify_only"
    }

    private lateinit var windowManager: WindowManager
    private lateinit var overlayButton: View
    private lateinit var overlayPanel: View

    private val serviceJob = Job()
    private val scope = CoroutineScope(Dispatchers.Main + serviceJob)

    private val handler = Handler(mainLooper)

    private var overlaysAdded = false

    override fun onBind(intent: Intent?): IBinder? = null

    // 兼容提示：根据厂商给出更贴切的引导
    private fun miuiTips(): String {
        return if (Build.MANUFACTURER.equals("Xiaomi", true)) {
            "请在 MIUI 设置中开启：显示悬浮窗、后台弹出界面、自启动，并允许通知。"
        } else {
            "请在系统设置中开启“在其他应用上层显示”与通知权限，必要时允许自启动/后台弹出界面。"
        }
    }

    private fun broadcast(status: String, msg: String? = null) {
        val i = Intent("com.example.netprobeoverlay.OVERLAY_EVENT")
        i.putExtra("status", status)
        if (msg != null) i.putExtra("msg", msg)
        try { sendBroadcast(i) } catch (_: Exception) {}
    }

    override fun onCreate() {
        super.onCreate()
        // 仅负责把服务拉到前台，避免后台限制；具体业务移到 onStartCommand
        startAsForeground()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val mode = intent?.getStringExtra(EXTRA_MODE)
        val notifyOnly = (mode == MODE_NOTIFY_ONLY)

        if (notifyOnly) {
            // 仅测试通知：不触发悬浮窗权限与窗口添加
            broadcast("ready", "仅通知测试")
            return START_NOT_STICKY
        }

        // 正常模式：检查权限并添加悬浮窗
        if (!overlaysAdded) {
            if (!hasOverlayPermission()) {
                Toast.makeText(this, getString(R.string.overlay_permission_rationale), Toast.LENGTH_LONG).show()
                broadcast("failed", "缺少悬浮窗权限")
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }

            try {
                windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
                val addedBtn = addOverlayButtonSafely()
                val addedPanel = addOverlayPanelSafely()
                if (!addedBtn || !addedPanel) {
                    Toast.makeText(this, "悬浮窗添加失败，" + miuiTips(), Toast.LENGTH_LONG).show()
                    broadcast("failed", "系统限制或窗口添加失败")
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                    return START_NOT_STICKY
                } else {
                    overlaysAdded = true
                    broadcast("ready", null)
                }
            } catch (e: Exception) {
                Log.e("OverlayService", "Exception adding overlays", e)
                Toast.makeText(this, "悬浮窗异常：${e.message}", Toast.LENGTH_LONG).show()
                broadcast("failed", e.javaClass.simpleName + ": " + (e.message ?: ""))
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        try { windowManager.removeView(overlayButton) } catch (_: Exception) {}
        try { windowManager.removeView(overlayPanel) } catch (_: Exception) {}
        serviceJob.cancel()
    }

    private fun hasOverlayPermission(): Boolean = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        Settings.canDrawOverlays(this)
    } else true

    private fun startAsForeground() {
        val channelId = "netprobe_overlay"
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // 提升重要级别，尽量避免被 MIUI 静默隐藏
            val channel = NotificationChannel(channelId, "NetProbeOverlay", NotificationManager.IMPORTANCE_HIGH)
            channel.setShowBadge(true)
            channel.enableVibration(true)
            channel.enableLights(true)
            channel.lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            nm.createNotificationChannel(channel)
        }
        val intent = Intent(this, MainActivity::class.java)
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
        val pi = PendingIntent.getActivity(this, 0, intent, flags)
        val builder = NotificationCompat.Builder(this, channelId)
            .setContentTitle("NetProbe Overlay 运行中")
            .setContentText("点击回到应用查看状态")
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentIntent(pi)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
        if (Build.VERSION.SDK_INT >= 31) {
            builder.setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        }
        val notif: Notification = builder.build()
        try {
            startForeground(1, notif)
        } catch (e: Exception) {
            // Android 13+ 未授予通知权限或厂商限制导致启动前台失败
            Toast.makeText(this, "前台通知启动失败：${e.message}", Toast.LENGTH_LONG).show()
            broadcast("failed", "前台通知启动失败，可能未授予通知权限")
            stopSelf()
        }
    }

    private fun commonLayoutParams(): WindowManager.LayoutParams {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or // 不抢焦点，不影响其他区域触摸
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 50
            y = 300
        }
    }

    private fun addOverlayButtonSafely(): Boolean {
        return try {
            val inflater = LayoutInflater.from(this)
            overlayButton = inflater.inflate(R.layout.overlay_button, null)
            val params = commonLayoutParams()
            windowManager.addView(overlayButton, params)

            overlayButton.setOnTouchListener(object : View.OnTouchListener {
                private var lastX = 0f
                private var lastY = 0f
                private var downX = 0f
                private var downY = 0f
                private var isDragging = false

                override fun onTouch(v: View?, event: MotionEvent?): Boolean {
                    event ?: return false
                    val lp = overlayButton.layoutParams as WindowManager.LayoutParams
                    when (event.action) {
                        MotionEvent.ACTION_DOWN -> {
                            isDragging = false
                            lastX = event.rawX
                            lastY = event.rawY
                            downX = lastX
                            downY = lastY
                            return true
                        }
                        MotionEvent.ACTION_MOVE -> {
                            val dx = event.rawX - lastX
                            val dy = event.rawY - lastY
                            if (!isDragging && (kotlin.math.abs(event.rawX - downX) > 10 || kotlin.math.abs(event.rawY - downY) > 10)) {
                                isDragging = true
                            }
                            if (isDragging) {
                                lp.x += dx.toInt()
                                lp.y += dy.toInt()
                                windowManager.updateViewLayout(overlayButton, lp)
                                lastX = event.rawX
                                lastY = event.rawY
                            }
                            return true
                        }
                        MotionEvent.ACTION_UP -> {
                            if (!isDragging) {
                                // 点击行为：触发测试
                                triggerTestAndShowPanel()
                            }
                            return true
                        }
                    }
                    return false
                }
            })
            true
        } catch (e: Exception) {
            Log.e("OverlayService", "Failed to add overlay button", e)
            Toast.makeText(this, "无法显示悬浮窗按钮，可能被系统限制。" + miuiTips(), Toast.LENGTH_LONG).show()
            broadcast("failed", e.javaClass.simpleName + ": " + (e.message ?: ""))
            false
        }
    }

    private fun addOverlayPanelSafely(): Boolean {
        return try {
            val inflater = LayoutInflater.from(this)
            overlayPanel = inflater.inflate(R.layout.overlay_panel, null)
            overlayPanel.visibility = View.GONE
            val params = commonLayoutParams().apply {
                y += 70 // 面板略微在按钮下方
                flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE // 不拦截触摸，完全透传
            }
            windowManager.addView(overlayPanel, params)
            true
        } catch (e: Exception) {
            Log.e("OverlayService", "Failed to add overlay panel", e)
            Toast.makeText(this, "无法显示悬浮窗面板，可能被系统限制。" + miuiTips(), Toast.LENGTH_LONG).show()
            broadcast("failed", e.javaClass.simpleName + ": " + (e.message ?: ""))
            false
        }
    }

    private fun showPanelFor(ms: Long) {
        overlayPanel.visibility = View.VISIBLE
        handler.removeCallbacksAndMessages(null)
        handler.postDelayed({ overlayPanel.visibility = View.GONE }, ms)
    }

    private fun triggerTestAndShowPanel() {
        val tvNode = overlayPanel.findViewById<TextView>(R.id.tvNode)
        val tvAddr = overlayPanel.findViewById<TextView>(R.id.tvAddr)
        val tvLatency = overlayPanel.findViewById<TextView>(R.id.tvLatency)
        val tvBandwidth = overlayPanel.findViewById<TextView>(R.id.tvBandwidth)

        tvNode.text = "节点：当前"
        tvAddr.text = "地址：通过系统代理"
        tvLatency.text = "延迟：测试中..."
        tvBandwidth.text = "带宽：测试中..."

        showPanelFor(5000)

        scope.launch {
            val latency = NetProbe.measureLatency()
            withContext(Dispatchers.Main) {
                tvLatency.text = if (latency >= 0) "延迟：${latency} ms" else "延迟：失败"
            }

            val bandwidth = NetProbe.measureBandwidth()
            withContext(Dispatchers.Main) {
                tvBandwidth.text = if (bandwidth >= 0) "带宽：${bandwidth} Mbps" else "带宽：失败"
            }
        }
    }
}