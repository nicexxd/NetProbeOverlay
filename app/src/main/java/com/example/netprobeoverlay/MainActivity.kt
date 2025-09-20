package com.example.netprobeoverlay

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.netprobeoverlay.overlay.OverlayService

class MainActivity : AppCompatActivity() {

    companion object {
        private const val REQ_OVERLAY = 1000
        private const val REQ_POST_NOTIF = 1001
    }

    private var pendingStart = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val btn = Button(this).apply {
            text = getString(R.string.start_overlay)
            setOnClickListener { startOverlay() }
            setPadding(40, 40, 40, 40)
        }
        setContentView(btn)
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
        if (Build.VERSION.SDK_INT >= 26) {
            ContextCompat.startForegroundService(this, intent)
        } else {
            startService(intent)
        }
        Toast.makeText(this, getString(R.string.start_overlay), Toast.LENGTH_SHORT).show()
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
            }
        }
    }
}