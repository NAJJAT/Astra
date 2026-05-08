package com.meshconnect.client

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat

class MainActivity : AppCompatActivity() {

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

        val btn    = findViewById<Button>(R.id.btn_start)
        val status = findViewById<TextView>(R.id.tv_status)

        btn.setOnClickListener {
            val svc = Intent(this, WebSocketService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(svc)
            } else {
                startService(svc)
            }
            status.text = "Service started!\nDevice will appear Online in dashboard."
            btn.isEnabled = false
            btn.text = "Running…"

            // Move app to background so service continues as foreground
            moveTaskToBack(true)
        }
    }
}
