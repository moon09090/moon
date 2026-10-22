package com.moon.assistant

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(48, 96, 48, 48)
        }
        col.addView(TextView(this).apply {
            text = "Moon Assistant\n\nKeep moon_server.py running in Termux."
            textSize = 18f
            gravity = Gravity.CENTER
        })
        col.addView(Button(this).apply {
            text = "1. Allow permissions"
            setOnClickListener { askPermissions() }
        })
        col.addView(Button(this).apply {
            text = "2. Start Moon"
            setOnClickListener { startForegroundService(Intent(this@MainActivity, MoonService::class.java)) }
        })
        col.addView(Button(this).apply {
            text = "Stop Moon"
            setOnClickListener { stopService(Intent(this@MainActivity, MoonService::class.java)) }
        })
        setContentView(col)
    }

    private fun askPermissions() {
        val perms = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) perms.add(Manifest.permission.POST_NOTIFICATIONS)
        requestPermissions(perms.toTypedArray(), 1)
    }

    override fun onRequestPermissionsResult(code: Int, p: Array<out String>, r: IntArray) {
        super.onRequestPermissionsResult(code, p, r)
        if (!Settings.canDrawOverlays(this)) {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }
    }
}
