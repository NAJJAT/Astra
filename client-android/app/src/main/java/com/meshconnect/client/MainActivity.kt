package com.meshconnect.client

import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
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

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(android.Manifest.permission.POST_NOTIFICATIONS),
                1
            )
        }

        startBtn   = findViewById(R.id.btn_start)
        statusView = findViewById(R.id.tv_status)

        startBtn.setOnClickListener {
            val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            projectionLauncher.launch(mpm.createScreenCaptureIntent())
        }
    }
}
