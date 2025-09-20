package com.example.netprobeoverlay

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.netprobeoverlay.overlay.OverlayService
import androidx.appcompat.app.AlertDialog

class MainActivity : AppCompatActivity() {

    companion object {
        private const val REQ_OVERLAY = 1000
        private const val REQ_POST_NOTIF = 1001
        private const val ACTION_OVERLAY_EVENT = "com.example.netprobeoverlay.OVERLAY_EVENT"
    }

    private var pendingStart = false
    private var waitingForReady = false
    private val handler = Handler(Looper.getMainLooper())

    private var overlayReceiver: BroadcastReceiver? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val btn = Button(this).apply {
            text = getString(R.string.start_overlay)
            setOnClickListener { startOverlay() }
            setOnLongClickListener {
                // 长按仅测试通知常驻，不添加悬浮窗
                startNotifyOnly()
                true
            }
            setPadding(40, 40, 40, 40)
        }
        setContentView(btn)
    }

    override fun onStart() {
        super.onStart()
        if (overlayReceiver == null) {
            overlayReceiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    if (intent?.action != ACTION_OVERLAY_EVENT) return
                    val status = intent.getStringExtra("status")
                    val msg = intent.getStringExtra("msg") ?: ""
                    waitingForReady = false
                    if (status == "ready") {
                        Toast.makeText(this@MainActivity, if (msg.isNotEmpty()) msg else "悬浮窗已启动", Toast.LENGTH_LONG).show()
                    } else if (status == "failed") {
                        Toast.makeText(this@MainActivity, "悬浮窗启动失败：$msg", Toast.LENGTH_LONG).show()
                        showResolutionDialog("悬浮窗启动失败：$msg")
                    }
                }
            }
            registerReceiver(overlayReceiver, IntentFilter(ACTION_OVERLAY_EVENT))
        }
    }

    override fun onStop() {
        super.onStop()
        overlayReceiver?.let { unregisterReceiver(it) }
        overlayReceiver = null
    }

    override fun onResume() {
        super.onResume()
        if (pendingStart && Settings.canDrawOverlays(this)) {
            // 悬浮窗权限刚授予后继续流程
            maybeRequestPostNotifAndStart()
            pendingStart = false
        }
    }

    private fun startOverlay() {
        if (!Settings.canDrawOverlays(this)) {
            pendingStart = true
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            try {
                startActivity(intent)
            } catch (_: Exception) {
                Toast.makeText(this, getString(R.string.overlay_permission_rationale), Toast.LENGTH_LONG).show()
            }
            return
        }
        maybeRequestPostNotifAndStart()
    }

    private fun startNotifyOnly() {
        // 直接尝试启动仅通知模式，便于诊断“通知是否可见/前台服务是否能拉起”
        val intent = Intent(this, OverlayService::class.java).apply {
            putExtra(OverlayService.EXTRA_MODE, OverlayService.MODE_NOTIFY_ONLY)
        }
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                ContextCompat.startForegroundService(this, intent)
            } else {
                startService(intent)
            }
            Toast.makeText(this, "仅通知测试：已尝试启动前台服务", Toast.LENGTH_SHORT).show()
            waitingForReady = true
            handler.postDelayed({
                if (waitingForReady) {
                    waitingForReady = false
                    Toast.makeText(this, "未检测到通知，可能被系统限制或通知权限未开。", Toast.LENGTH_LONG).show()
                    showResolutionDialog("未检测到通知，可能被系统限制或通知权限未开。")
                }
            }, 3000)
        } catch (e: Exception) {
            Toast.makeText(this, "仅通知测试启动失败：${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun maybeRequestPostNotifAndStart() {
        if (Build.VERSION.SDK_INT >= 33) {
            val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_POST_NOTIF)
                return
            }
        }
        actuallyStartOverlayService()
    }

    private fun actuallyStartOverlayService() {
        val intent = Intent(this, OverlayService::class.java)
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                ContextCompat.startForegroundService(this, intent)
            } else {
                startService(intent)
            }
            Toast.makeText(this, getString(R.string.start_overlay), Toast.LENGTH_SHORT).show()
            // 等待服务回报“ready”，超过 3 秒仍无回报则给出引导提示
            waitingForReady = true
            handler.postDelayed({
                if (waitingForReady) {
                    waitingForReady = false
                    Toast.makeText(this, "未检测到悬浮窗，可能被系统限制。请检查悬浮窗权限/通知权限/后台显示权限。", Toast.LENGTH_LONG).show()
                    showResolutionDialog("未检测到悬浮窗，可能被系统限制。")
                }
            }, 3000)
        } catch (e: Exception) {
            Toast.makeText(this, "启动前台服务失败：${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_POST_NOTIF) {
            val granted = grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED
            if (granted) {
                actuallyStartOverlayService()
            } else {
                // 没有通知权限也尽量尝试启动，但可能影响前台通知显示
                actuallyStartOverlayService()
                showResolutionDialog("通知权限未授予，可能导致前台服务通知不可见。")
            }
        }
    }

    private fun showResolutionDialog(reason: String?) {
        if (isFinishing) return
        val isMiui = Build.MANUFACTURER.equals("Xiaomi", true)
        val message = buildString {
            if (!reason.isNullOrBlank()) append(reason).append('\n')
            append("请按以下步骤排查：\n")
            append("1) 开启“在其他应用上层显示(悬浮窗)”\n")
            append("2) 开启“通知权限”，允许常驻通知\n")
            if (isMiui) append("3) 在 MIUI 设置中开启“自启动/后台弹出界面/省电策略为无限制”\n")
        }
        AlertDialog.Builder(this)
            .setTitle("需要权限与系统设置")
            .setMessage(message)
            .setCancelable(true)
            .setPositiveButton("打开悬浮窗权限") { _, _ -> openOverlayPermissionSettings() }
            .setNegativeButton("打开通知设置") { _, _ -> openAppNotificationSettings() }
            .apply {
                if (isMiui) {
                    setNeutralButton("MIUI 自启动管理") { _, _ -> openMiuiAutoStartSettings() }
                }
            }
            .show()
    }

    private fun openOverlayPermissionSettings() {
        val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
        try { startActivity(intent) } catch (_: Exception) {
            val fallback = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
            try { startActivity(fallback) } catch (_: Exception) {}
        }
    }

    private fun openAppNotificationSettings() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                    putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                }
                startActivity(intent)
            } else {
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
                startActivity(intent)
            }
        } catch (_: Exception) {
            val fallback = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
            try { startActivity(fallback) } catch (_: Exception) {}
        }
    }

    private fun openMiuiAutoStartSettings() {
        val intents = listOf(
            Intent().apply {
                setClassName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity")
            },
            Intent("miui.intent.action.APP_PERM_EDITOR").apply {
                setClassName("com.miui.securitycenter", "com.miui.permcenter.permissions.PermissionsEditorActivity")
                putExtra("extra_pkgname", packageName)
            }
        )
        for (it in intents) {
            try { startActivity(it); return } catch (_: Exception) {}
        }
        // 兜底
        val fallback = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
        try { startActivity(fallback) } catch (_: Exception) {}
    }
}