package com.meshconnect.client

import android.app.*
import android.content.Intent
import android.os.*
import android.provider.Settings
import androidx.core.app.NotificationCompat
import okhttp3.*
import okio.ByteString
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class WebSocketService : Service() {

    companion object {
        const val CHANNEL_ID  = "meshconnect_ch"
        const val NOTIF_ID    = 1
        const val SERVER_URL  = "ws://20.86.120.120:8082/ws"
    }

    private var client: OkHttpClient? = null
    private var ws: WebSocket? = null
    private val handler = Handler(Looper.getMainLooper())
    private var retryDelay = 3_000L

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIF_ID, buildNotif("Connecting…"))
        connect()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        ws?.close(1000, "stopped")
        client?.dispatcher?.executorService?.shutdown()
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun connect() {
        client = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(0,  TimeUnit.SECONDS)
            .pingInterval(30, TimeUnit.SECONDS)
            .build()

        val req = Request.Builder().url(SERVER_URL).build()
        ws = client!!.newWebSocket(req, listener())
    }

    private fun listener() = object : WebSocketListener() {
        override fun onOpen(ws: WebSocket, response: Response) {
            retryDelay = 3_000L
            notify("Connected")
            ws.send(deviceJson())
        }

        override fun onMessage(ws: WebSocket, text: String) {}
        override fun onMessage(ws: WebSocket, bytes: ByteString) {}

        override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
            notify("Reconnecting…")
            retry()
        }

        override fun onClosed(ws: WebSocket, code: Int, reason: String) {
            if (code != 1000) retry()
        }
    }

    private fun deviceJson(): String {
        val id = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)
        return JSONObject().apply {
            put("id",           id)
            put("name",         "${Build.MANUFACTURER} ${Build.MODEL}")
            put("model",        Build.MODEL)
            put("manufacturer", Build.MANUFACTURER)
            put("os_version",   Build.VERSION.RELEASE)
        }.toString()
    }

    private fun retry() {
        handler.postDelayed({ connect() }, retryDelay)
        retryDelay = minOf(retryDelay * 2, 30_000L)
    }

    private fun notify(status: String) {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID, buildNotif(status))
    }

    private fun buildNotif(status: String): Notification {
        val pi = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("MeshConnect")
            .setContentText(status)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                CHANNEL_ID,
                "MeshConnect Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply { description = "Keeps device connected to MeshConnect" }
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(ch)
        }
    }
}
