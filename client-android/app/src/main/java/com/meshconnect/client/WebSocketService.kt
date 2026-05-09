package com.meshconnect.client

import android.app.*
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.*
import android.os.BatteryManager
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okio.ByteString
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

@Suppress("DEPRECATION")
class WebSocketService : Service() {

    companion object {
        const val CHANNEL_ID  = "meshconnect_ch"
        const val NOTIF_ID    = 1
        const val SERVER_URL  = "ws://20.86.120.120:8082/ws"
        const val EXTRA_PROJECTION_RESULT_CODE = "projection_result_code"
        const val EXTRA_PROJECTION_DATA        = "projection_data"
        private const val TAG = "WebSocketService"
        private const val STATUS_INTERVAL_MS = 30_000L
    }

    private var client: OkHttpClient? = null
    private var ws: WebSocket? = null
    private val handler = Handler(Looper.getMainLooper())
    private var retryDelay = 3_000L

    private var projectionResultCode = 0
    private var projectionData: Intent? = null
    private var mediaProjection: MediaProjection? = null
    private val captureThread = HandlerThread("ScreenCapture").apply { start() }
    private val captureHandler = Handler(captureThread.looper)
    private val androidId: String by lazy {
        Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)
    }

    private val statusRunnable = object : Runnable {
        override fun run() {
            ws?.send(statusJson())
            handler.postDelayed(this, STATUS_INTERVAL_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notif = buildNotif("Connecting…")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notif,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC or
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIF_ID, notif)
        }

        if (intent != null) {
            projectionResultCode = intent.getIntExtra(EXTRA_PROJECTION_RESULT_CODE, 0)
            projectionData = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(EXTRA_PROJECTION_DATA, Intent::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(EXTRA_PROJECTION_DATA)
            }
        }

        if (ws == null) connect()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        handler.removeCallbacks(statusRunnable)
        ws?.close(1000, "stopped")
        client?.dispatcher?.executorService?.shutdown()
        handler.removeCallbacksAndMessages(null)
        try { mediaProjection?.stop() } catch (_: Exception) {}
        captureThread.quitSafely()
        super.onDestroy()
    }

    private fun connect() {
        client = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(0,  TimeUnit.SECONDS)
            .pingInterval(30, TimeUnit.SECONDS)
            .build()

        val req = Request.Builder().url(SERVER_URL).build()
        Log.i(TAG, "Connecting to $SERVER_URL")
        ws = client!!.newWebSocket(req, listener())
    }

    private fun listener() = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            retryDelay = 3_000L
            Log.i(TAG, "Connected to $SERVER_URL")
            notify("Connected")
            webSocket.send(deviceJson())
            webSocket.send(statusJson())
            handler.postDelayed(statusRunnable, STATUS_INTERVAL_MS)
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            handleIncoming(webSocket, text)
        }
        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {}

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            Log.e(TAG, "Connection failed for $SERVER_URL", t)
            handler.removeCallbacks(statusRunnable)
            notify("Reconnecting…")
            retry()
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            Log.i(TAG, "Closed: code=$code reason=$reason")
            handler.removeCallbacks(statusRunnable)
            if (code != 1000) retry()
        }
    }

    private fun deviceJson(): String {
        return JSONObject().apply {
            put("id",           androidId)
            put("name",         "${Build.MANUFACTURER} ${Build.MODEL}")
            put("model",        Build.MODEL)
            put("manufacturer", Build.MANUFACTURER)
            put("os_version",   Build.VERSION.RELEASE)
        }.toString()
    }

    private fun statusJson(): String {
        val bm = getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val battery = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val charging = bm.isCharging

        val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)
        val ramUsed = mi.totalMem - mi.availMem

        val statFs = StatFs(Environment.getDataDirectory().path)
        val storageTotal = statFs.totalBytes
        val storageUsed = storageTotal - statFs.availableBytes

        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork)
        val network = when {
            caps == null -> "none"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)     -> "wifi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
            else -> "other"
        }

        return JSONObject().apply {
            put("type",          "status")
            put("battery",       battery)
            put("charging",      charging)
            put("ram_used",      ramUsed)
            put("ram_total",     mi.totalMem)
            put("storage_used",  storageUsed)
            put("storage_total", storageTotal)
            put("network",       network)
            put("uptime",        SystemClock.elapsedRealtime())
        }.toString()
    }

    private fun handleIncoming(webSocket: WebSocket, text: String) {
        try {
            val msg = JSONObject(text)
            if (msg.optString("type") != "command") return
            val command = msg.optString("command")
            val id = msg.optString("id")
            Log.i(TAG, "command received: $command (id=$id)")

            when (command) {
                "ping" -> sendResult(webSocket, id, command, true, "pong")
                "get_info" -> {
                    webSocket.send(statusJson())
                    sendResult(webSocket, id, command, true, "status sent")
                }
                "vibrate" -> {
                    val ok = vibrate()
                    sendResult(webSocket, id, command, ok,
                        if (ok) "vibrated" else "no vibrator")
                }
                "screenshot" -> captureScreenshot(webSocket, id)
                else -> sendResult(webSocket, id, command, false, "unknown command")
            }
        } catch (e: Exception) {
            Log.e(TAG, "handleIncoming error", e)
        }
    }

    private fun sendResult(webSocket: WebSocket, id: String, command: String,
                           success: Boolean, message: String) {
        val payload = JSONObject().apply {
            put("type", "command_result")
            put("id", id)
            put("command", command)
            put("success", success)
            put("message", message)
        }.toString()
        webSocket.send(payload)
    }

    private fun vibrate(): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                vm.defaultVibrator.vibrate(
                    VibrationEffect.createOneShot(500, VibrationEffect.DEFAULT_AMPLITUDE)
                )
            } else {
                val v = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    v.vibrate(VibrationEffect.createOneShot(500, VibrationEffect.DEFAULT_AMPLITUDE))
                } else {
                    v.vibrate(500)
                }
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "vibrate failed", e)
            false
        }
    }

    private fun captureScreenshot(webSocket: WebSocket, requestId: String) {
        if (projectionData == null || projectionResultCode == 0) {
            sendResult(webSocket, requestId, "screenshot", false, "no projection permission")
            return
        }
        captureHandler.post {
            try {
                if (mediaProjection == null) {
                    val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE)
                            as MediaProjectionManager
                    mediaProjection = mpm.getMediaProjection(projectionResultCode, projectionData!!)
                    mediaProjection?.registerCallback(object : MediaProjection.Callback() {
                        override fun onStop() {
                            Log.w(TAG, "MediaProjection stopped by system")
                            mediaProjection = null
                        }
                    }, captureHandler)
                }
                val projection = mediaProjection ?: run {
                    sendResult(webSocket, requestId, "screenshot", false, "projection unavailable")
                    return@post
                }
                doCapture(projection, webSocket, requestId)
            } catch (e: Exception) {
                Log.e(TAG, "captureScreenshot error", e)
                sendResult(webSocket, requestId, "screenshot", false, "error: ${e.message}")
            }
        }
    }

    private fun doCapture(projection: MediaProjection, webSocket: WebSocket, requestId: String) {
        val metrics = resources.displayMetrics
        val srcWidth = metrics.widthPixels
        val srcHeight = metrics.heightPixels
        val density = metrics.densityDpi

        val maxWidth = 720
        val width = if (srcWidth > maxWidth) maxWidth else srcWidth
        val height = (srcHeight.toLong() * width / srcWidth).toInt()

        val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        var virtualDisplay: VirtualDisplay? = null
        var captured = false

        reader.setOnImageAvailableListener({ r ->
            if (captured) return@setOnImageAvailableListener
            val image = r.acquireLatestImage() ?: return@setOnImageAvailableListener
            captured = true
            try {
                val bitmap = imageToBitmap(image, width, height)
                val baos = ByteArrayOutputStream()
                bitmap.compress(Bitmap.CompressFormat.JPEG, 65, baos)
                bitmap.recycle()
                val jpeg = baos.toByteArray()
                Log.i(TAG, "screenshot ${jpeg.size}B (${width}x${height})")

                val ok = uploadScreenshot(requestId, jpeg)
                sendResult(webSocket, requestId, "screenshot", ok,
                    if (ok) "${jpeg.size} bytes" else "upload failed")
            } catch (e: Exception) {
                Log.e(TAG, "imageReader error", e)
                sendResult(webSocket, requestId, "screenshot", false, "encode error: ${e.message}")
            } finally {
                image.close()
                virtualDisplay?.release()
                reader.close()
            }
        }, captureHandler)

        virtualDisplay = projection.createVirtualDisplay(
            "MeshConnectScreenshot",
            width, height, density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader.surface, null, captureHandler
        )

        captureHandler.postDelayed({
            if (!captured) {
                Log.w(TAG, "screenshot timeout")
                virtualDisplay?.release()
                reader.close()
                sendResult(webSocket, requestId, "screenshot", false, "capture timeout")
            }
        }, 4000)
    }

    private fun imageToBitmap(image: Image, width: Int, height: Int): Bitmap {
        val plane = image.planes[0]
        val buffer = plane.buffer
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val rowPadding = rowStride - pixelStride * width
        val bmpWidth = width + rowPadding / pixelStride
        val raw = Bitmap.createBitmap(bmpWidth, height, Bitmap.Config.ARGB_8888)
        raw.copyPixelsFromBuffer(buffer)
        return if (rowPadding == 0) raw
        else Bitmap.createBitmap(raw, 0, 0, width, height).also { raw.recycle() }
    }

    private fun uploadScreenshot(requestId: String, jpeg: ByteArray): Boolean {
        val httpBase = SERVER_URL.replaceFirst("ws://", "http://")
            .replaceFirst("wss://", "https://")
            .removeSuffix("/ws")
        val url = "$httpBase/api/devices/$androidId/screenshots/$requestId"
        val body = jpeg.toRequestBody("image/jpeg".toMediaType())
        val req = Request.Builder().url(url).post(body).build()
        return try {
            client!!.newCall(req).execute().use { it.isSuccessful }
        } catch (e: Exception) {
            Log.e(TAG, "upload failed", e)
            false
        }
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
