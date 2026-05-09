package com.meshconnect.client

import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat

class MainActivity : AppCompatActivity() {

    private lateinit var statusView: TextView
    private lateinit var startBtn: Button

    private val projectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val svc = Intent(this, WebSocketService::class.java).apply {
            putExtra(WebSocketService.EXTRA_PROJECTION_RESULT_CODE, result.resultCode)
            if (result.data != null) {
                putExtra(WebSocketService.EXTRA_PROJECTION_DATA, result.data)
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(svc)
        } else {
            startService(svc)
        }
        statusView.text = if (result.resultCode == RESULT_OK)
            "Service started!\nScreen-capture is enabled.\nDevice will appear Online in dashboard."
        else
            "Service started without screenshot.\nOther commands still work."
        startBtn.isEnabled = false
        startBtn.text = "Running…"
        moveTaskToBack(true)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val perms = mutableListOf(
            android.Manifest.permission.ACCESS_FINE_LOCATION,
            android.Manifest.permission.ACCESS_COARSE_LOCATION,
            android.Manifest.permission.CAMERA
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms.add(android.Manifest.permission.POST_NOTIFICATIONS)
        }
        ActivityCompat.requestPermissions(this, perms.toTypedArray(), 1)

        startBtn   = findViewById(R.id.btn_start)
        statusView = findViewById(R.id.tv_status)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
            !Environment.isExternalStorageManager()) {
            statusView.text = "Tap Start Service.\n(File browser needs ‘All files access’ — you'll be prompted.)"
        }

        startBtn.setOnClickListener {
            ensureFilesPermissionThenStart()
        }
    }

    private fun ensureFilesPermissionThenStart() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
            !Environment.isExternalStorageManager()) {
            try {
                val intent = Intent(
                    Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:$packageName")
                )
                startActivity(intent)
                statusView.text = "Grant ‘All files access’, then come back and tap Start Service again."
                return
            } catch (_: Exception) {
                // Fall through and start anyway — file browser just won't work
            }
        }
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projectionLauncher.launch(mpm.createScreenCaptureIntent())
    }
}
