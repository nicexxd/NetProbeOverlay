package com.example.netprobeoverlay

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity
import com.example.netprobeoverlay.overlay.OverlayService

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val btn = Button(this).apply {
            text = getString(R.string.start_overlay)
            setOnClickListener { startOverlay() }
            setPadding(40, 40, 40, 40)
        }
        setContentView(btn)
    }

    private fun startOverlay() {
        if (!Settings.canDrawOverlays(this)) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
            return
        }
        if (Build.VERSION.SDK_INT >= 33) {
            // Android 13+ 通知权限（前台服务通知）
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1001)
        }
        startService(Intent(this, OverlayService::class.java))
    }
}