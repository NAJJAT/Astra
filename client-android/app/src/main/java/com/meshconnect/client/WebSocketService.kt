package com.meshconnect.client

import android.Manifest
import android.app.ActivityManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.graphics.PixelFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.location.Location
import android.location.LocationManager
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.StatFs
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.URLEncoder
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class WebSocketService : Service() {

    companion object {
        const val CHANNEL_ID = "meshconnect_ch"
        const val MESSAGE_CHANNEL_ID = "meshconnect_msg"
        const val NOTIF_ID = 1
        const val SERVER_URL = "wss://meshconnect.duckdns.org/ws"
        // Must match MESH_TOKEN in server/.env
        const val AUTH_TOKEN = "6c2b3174134098b304f2f7f6b433476b4c2d5eeb1725ee61966fc55ab4fdd542"
        const val EXTRA_PROJECTION_RESULT_CODE = "projection_result_code"
        const val EXTRA_PROJECTION_DATA        = "projection_data"
        private const val TAG = "WebSocketService"
        private const val STATUS_INTERVAL_MS = 30_000L
    }

    private var client: OkHttpClient? = null
    private var ws: WebSocket? = null
    private val handler = Handler(Looper.getMainLooper())
    private var retryDelay = 3_000L
    private val ioExecutor: ExecutorService = Executors.newCachedThreadPool()

    private var projectionResultCode = 0
    private var projectionData: Intent? = null
    private var mediaProjection: MediaProjection? = null
    private val captureThread = HandlerThread("Capture").apply { start() }
    private val captureHandler = Handler(captureThread.looper)

    private val androidId: String by lazy {
        Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)
    }
    private var nextMessageNotifId = 100

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
        if (intent != null) {
            val rc = intent.getIntExtra(EXTRA_PROJECTION_RESULT_CODE, 0)
            if (rc != 0) {
                projectionResultCode = rc
                projectionData = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(EXTRA_PROJECTION_DATA, Intent::class.java)
                } else {
                    intent.getParcelableExtra(EXTRA_PROJECTION_DATA)
                }
            }
        }

        val notif = buildNotif("Connecting…")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notif, currentForegroundTypes())
        } else {
            startForeground(NOTIF_ID, notif)
        }

        if (ws == null) connect()
        return START_STICKY
    }

    private fun currentForegroundTypes(): Int {
        var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        if (projectionData != null) {
            type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        }
        if (hasLocationPermission()) {
            type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED) {
            type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
        }
        return type
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        handler.removeCallbacks(statusRunnable)
        handler.removeCallbacksAndMessages(null)
        ws?.close(1000, "stopped")
        client?.dispatcher?.executorService?.shutdown()
        ioExecutor.shutdownNow()
        try { mediaProjection?.stop() } catch (_: Exception) {}
        captureThread.quitSafely()
        super.onDestroy()
    }

    private fun connect() {
        client = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.SECONDS)
            .pingInterval(30, TimeUnit.SECONDS)
            .build()

        val req = Request.Builder()
            .url(SERVER_URL)
            .header("Authorization", "Bearer $AUTH_TOKEN")
            .build()
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
            handler.removeCallbacks(statusRunnable)
            handler.postDelayed(statusRunnable, STATUS_INTERVAL_MS)
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            handleIncoming(webSocket, text)
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) = Unit

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
                "screenshot"    -> captureScreenshot(webSocket, id)
                "camera"        -> captureCamera(webSocket, id, msg.optString("facing", "back"))
                "ls"            -> handleLs(webSocket, id, msg.optString("path", ""))
                "download_file" -> handleDownloadFile(webSocket, id, msg.optString("path", ""))
                "upload_file"   -> handleUploadFile(
                    webSocket, id,
                    msg.optString("upload_id"),
                    msg.optString("filename", "upload.bin"),
                    msg.optString("dest_dir", Environment.getExternalStorageDirectory().path + "/Download")
                )
                "notify" -> {
                    val title = msg.optString("title", "MeshConnect").ifBlank { "MeshConnect" }
                    val body = msg.optString("text", "")
                    showMessage(title, body)
                    sendResult(webSocket, id, command, true, "notification shown")
                }
                else -> sendResult(webSocket, id, command, false, "unsupported command")
            }
        } catch (e: Exception) {
            Log.e(TAG, "handleIncoming error", e)
        }
    }

    private fun deviceJson(): String {
        return JSONObject().apply {
            put("id", androidId)
            put("name", "${Build.MANUFACTURER} ${Build.MODEL}")
            put("model", Build.MODEL)
            put("manufacturer", Build.MANUFACTURER)
            put("os_version", Build.VERSION.RELEASE)
        }.toString()
    }

    private fun statusJson(): String {
        val bm = getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val battery = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)

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

        val json = JSONObject().apply {
            put("type", "status")
            put("battery", battery)
            put("charging", bm.isCharging)
            put("ram_used", ramUsed)
            put("ram_total", mi.totalMem)
            put("storage_used", storageUsed)
            put("storage_total", storageTotal)
            put("network", network)
            put("uptime", SystemClock.elapsedRealtime())
        }
        getBestLocation()?.let { loc ->
            json.put("latitude",          loc.latitude)
            json.put("longitude",         loc.longitude)
            json.put("location_accuracy", loc.accuracy.toDouble())
            json.put("location_time",     loc.time)
        }
        return json.toString()
    }

    private fun hasLocationPermission(): Boolean {
        return ContextCompat.checkSelfPermission(this,
            Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this,
            Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
    }

    private fun getBestLocation(): Location? {
        if (!hasLocationPermission()) return null
        val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        var best: Location? = null
        for (provider in listOf(
            LocationManager.GPS_PROVIDER,
            LocationManager.NETWORK_PROVIDER,
            LocationManager.PASSIVE_PROVIDER
        )) {
            try {
                if (!lm.isProviderEnabled(provider)) continue
                val loc = lm.getLastKnownLocation(provider) ?: continue
                if (best == null || loc.accuracy < best.accuracy) best = loc
            } catch (e: Exception) {
                // SecurityException if permission revoked, IllegalArgumentException if provider unknown
            }
        }
        return best
    }

    @Suppress("DEPRECATION")
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

    /* ── Screenshot via MediaProjection ── */

    private fun captureScreenshot(webSocket: WebSocket, requestId: String) {
        if (projectionData == null || projectionResultCode == 0) {
            sendResult(webSocket, requestId, "screenshot", false, "no projection permission")
            return
        }
        captureHandler.post {
            try {
                if (mediaProjection == null) {
                    val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
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
                doCaptureScreen(projection, webSocket, requestId)
            } catch (e: Exception) {
                Log.e(TAG, "captureScreenshot error", e)
                sendResult(webSocket, requestId, "screenshot", false, "error: ${e.message}")
            }
        }
    }

    private fun doCaptureScreen(projection: MediaProjection, webSocket: WebSocket, requestId: String) {
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

    /* ── Camera via Camera2 ── */

    private fun captureCamera(webSocket: WebSocket, requestId: String, facing: String) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
            sendResult(webSocket, requestId, "camera", false, "no camera permission")
            return
        }
        captureHandler.post {
            try {
                doCaptureCamera(webSocket, requestId, facing)
            } catch (e: Exception) {
                Log.e(TAG, "captureCamera error", e)
                sendResult(webSocket, requestId, "camera", false, "error: ${e.message}")
            }
        }
    }

    @Suppress("MissingPermission")
    private fun doCaptureCamera(webSocket: WebSocket, requestId: String, facing: String) {
        val cm = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val targetFacing = if (facing == "front")
            CameraCharacteristics.LENS_FACING_FRONT
        else CameraCharacteristics.LENS_FACING_BACK

        val cameraId = cm.cameraIdList.firstOrNull { id ->
            cm.getCameraCharacteristics(id)
                .get(CameraCharacteristics.LENS_FACING) == targetFacing
        } ?: cm.cameraIdList.firstOrNull()

        if (cameraId == null) {
            sendResult(webSocket, requestId, "camera", false, "no camera found")
            return
        }

        val chars = cm.getCameraCharacteristics(cameraId)
        val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val sizes = map?.getOutputSizes(ImageFormat.JPEG)
        val size = sizes?.let { arr ->
            arr.filter { it.width <= 1920 }
                .maxByOrNull { it.width.toLong() * it.height }
        } ?: sizes?.first()
        val width = size?.width ?: 1280
        val height = size?.height ?: 720
        val sensorOrientation = chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0

        val reader = ImageReader.newInstance(width, height, ImageFormat.JPEG, 1)
        var captured = false
        var camera: CameraDevice? = null
        var session: CameraCaptureSession? = null

        fun cleanup() {
            try { session?.close() } catch (_: Exception) {}
            try { camera?.close() } catch (_: Exception) {}
            try { reader.close() } catch (_: Exception) {}
        }

        reader.setOnImageAvailableListener({ r ->
            if (captured) return@setOnImageAvailableListener
            val image = r.acquireLatestImage() ?: return@setOnImageAvailableListener
            captured = true
            try {
                val buf = image.planes[0].buffer
                val bytes = ByteArray(buf.remaining())
                buf.get(bytes)
                Log.i(TAG, "camera ${bytes.size}B (${width}x${height}, $facing)")
                val ok = uploadScreenshot(requestId, bytes)
                sendResult(webSocket, requestId, "camera", ok,
                    if (ok) "${bytes.size} bytes ($facing)" else "upload failed")
            } catch (e: Exception) {
                Log.e(TAG, "camera encode error", e)
                sendResult(webSocket, requestId, "camera", false, "error: ${e.message}")
            } finally {
                image.close()
                cleanup()
            }
        }, captureHandler)

        try {
            cm.openCamera(cameraId, object : CameraDevice.StateCallback() {
            override fun onOpened(c: CameraDevice) {
                camera = c
                try {
                    val req = c.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                        addTarget(reader.surface)
                        set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                        set(CaptureRequest.JPEG_ORIENTATION, sensorOrientation)
                    }
                    c.createCaptureSession(listOf(reader.surface),
                        object : CameraCaptureSession.StateCallback() {
                            override fun onConfigured(s: CameraCaptureSession) {
                                session = s
                                try {
                                    s.capture(req.build(), null, captureHandler)
                                } catch (e: Exception) {
                                    Log.e(TAG, "capture failed", e)
                                    sendResult(webSocket, requestId, "camera", false,
                                        "capture failed: ${e.message}")
                                    cleanup()
                                }
                            }
                            override fun onConfigureFailed(s: CameraCaptureSession) {
                                sendResult(webSocket, requestId, "camera", false, "session config failed")
                                cleanup()
                            }
                        }, captureHandler)
                } catch (e: Exception) {
                    Log.e(TAG, "camera setup failed", e)
                    sendResult(webSocket, requestId, "camera", false, "setup: ${e.message}")
                    cleanup()
                }
            }
            override fun onDisconnected(c: CameraDevice) { c.close() }
            override fun onError(c: CameraDevice, error: Int) {
                c.close()
                if (!captured) {
                    sendResult(webSocket, requestId, "camera", false, "camera error: $error")
                }
                cleanup()
            }
        }, captureHandler)
        } catch (e: Exception) {
            Log.e(TAG, "openCamera failed", e)
            sendResult(webSocket, requestId, "camera", false, "openCamera: ${e.message}")
            cleanup()
            return
        }

        captureHandler.postDelayed({
            if (!captured) {
                Log.w(TAG, "camera capture timeout")
                sendResult(webSocket, requestId, "camera", false, "capture timeout")
                cleanup()
            }
        }, 8000)
    }

    /* ── File browser ── */

    private fun handleLs(webSocket: WebSocket, id: String, requestedPath: String) {
        ioExecutor.execute {
            try {
                val target = if (requestedPath.isBlank())
                    Environment.getExternalStorageDirectory()
                else File(requestedPath)

                if (!target.exists()) {
                    sendResult(webSocket, id, "ls", false, "path not found: ${target.absolutePath}")
                    return@execute
                }
                if (!target.isDirectory) {
                    sendResult(webSocket, id, "ls", false, "not a directory")
                    return@execute
                }

                val files = target.listFiles() ?: emptyArray()
                val entries = JSONArray()
                files.sortedWith(compareByDescending<File> { it.isDirectory }
                    .thenBy { it.name.lowercase() })
                    .forEach { f ->
                        entries.put(JSONObject().apply {
                            put("name", f.name)
                            put("is_dir", f.isDirectory)
                            put("size", if (f.isDirectory) 0L else f.length())
                            put("modified", f.lastModified())
                        })
                    }

                val payload = JSONObject().apply {
                    put("type", "command_result")
                    put("id", id)
                    put("command", "ls")
                    put("success", true)
                    put("path", target.absolutePath)
                    put("parent", target.parentFile?.absolutePath ?: "")
                    put("entries", entries)
                }.toString()
                webSocket.send(payload)
            } catch (e: Exception) {
                Log.e(TAG, "ls failed", e)
                sendResult(webSocket, id, "ls", false, "error: ${e.message}")
            }
        }
    }

    private fun handleDownloadFile(webSocket: WebSocket, id: String, path: String) {
        ioExecutor.execute {
            try {
                val file = File(path)
                if (!file.exists()) {
                    sendResult(webSocket, id, "download_file", false, "file not found")
                    return@execute
                }
                if (!file.isFile) {
                    sendResult(webSocket, id, "download_file", false, "not a file")
                    return@execute
                }
                if (file.length() > 100L * 1024 * 1024) {
                    sendResult(webSocket, id, "download_file", false, "file too large (>100MB)")
                    return@execute
                }

                val httpBase = SERVER_URL.replaceFirst("ws://", "http://")
                    .replaceFirst("wss://", "https://")
                    .removeSuffix("/ws")
                val url = "$httpBase/api/devices/$androidId/files/$id"
                val body = file.asRequestBody("application/octet-stream".toMediaType())
                val req = Request.Builder()
                    .url(url)
                    .post(body)
                    .header("Authorization", "Bearer $AUTH_TOKEN")
                    .header("X-Filename", URLEncoder.encode(file.name, "UTF-8"))
                    .build()

                client?.newCall(req)?.execute()?.use { resp ->
                    if (resp.isSuccessful) {
                        val payload = JSONObject().apply {
                            put("type", "command_result")
                            put("id", id)
                            put("command", "download_file")
                            put("success", true)
                            put("filename", file.name)
                            put("size", file.length())
                        }.toString()
                        webSocket.send(payload)
                    } else {
                        sendResult(webSocket, id, "download_file", false,
                            "upload failed: HTTP ${resp.code}")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "download_file failed", e)
                sendResult(webSocket, id, "download_file", false, "error: ${e.message}")
            }
        }
    }

    private fun handleUploadFile(
        webSocket: WebSocket,
        id: String,
        uploadId: String,
        filename: String,
        destDir: String
    ) {
        ioExecutor.execute {
            try {
                if (uploadId.isBlank() || !uploadId.matches(Regex("^[0-9a-fA-F]+$"))) {
                    sendResult(webSocket, id, "upload_file", false, "invalid upload_id")
                    return@execute
                }

                val httpBase = SERVER_URL.replaceFirst("ws://", "http://")
                    .replaceFirst("wss://", "https://")
                    .removeSuffix("/ws")
                val url = "$httpBase/api/uploads/$uploadId"
                val req = Request.Builder()
                    .url(url)
                    .header("Authorization", "Bearer $AUTH_TOKEN")
                    .build()

                client?.newCall(req)?.execute()?.use { resp ->
                    if (!resp.isSuccessful) {
                        sendResult(webSocket, id, "upload_file", false,
                            "fetch failed: HTTP ${resp.code}")
                        return@use
                    }
                    val bytes = resp.body?.bytes()
                    if (bytes == null) {
                        sendResult(webSocket, id, "upload_file", false, "empty body")
                        return@use
                    }

                    val dir = File(destDir)
                    if (!dir.exists() && !dir.mkdirs()) {
                        sendResult(webSocket, id, "upload_file", false,
                            "cannot create dir: $destDir")
                        return@use
                    }
                    val safeName = File(filename).name
                    val destFile = File(dir, safeName)
                    destFile.outputStream().use { it.write(bytes) }

                    Log.i(TAG, "upload_file saved ${bytes.size}B to ${destFile.absolutePath}")
                    val payload = JSONObject().apply {
                        put("type", "command_result")
                        put("id", id)
                        put("command", "upload_file")
                        put("success", true)
                        put("path", destFile.absolutePath)
                        put("size", bytes.size)
                    }.toString()
                    webSocket.send(payload)
                }
            } catch (e: Exception) {
                Log.e(TAG, "upload_file failed", e)
                sendResult(webSocket, id, "upload_file", false, "error: ${e.message}")
            }
        }
    }

    /* ── HTTP upload helper (used by screenshot + camera) ── */

    private fun uploadScreenshot(requestId: String, jpeg: ByteArray): Boolean {
        val httpBase = SERVER_URL.replaceFirst("ws://", "http://")
            .replaceFirst("wss://", "https://")
            .removeSuffix("/ws")
        val url = "$httpBase/api/devices/$androidId/screenshots/$requestId"
        val body = jpeg.toRequestBody("image/jpeg".toMediaType())
        val req = Request.Builder()
            .url(url)
            .post(body)
            .header("Authorization", "Bearer $AUTH_TOKEN")
            .build()
        return try {
            client!!.newCall(req).execute().use { it.isSuccessful }
        } catch (e: Exception) {
            Log.e(TAG, "upload failed", e)
            false
        }
    }

    /* ── Helpers ── */

    private fun sendResult(
        webSocket: WebSocket,
        id: String,
        command: String,
        success: Boolean,
        message: String
    ) {
        val payload = JSONObject().apply {
            put("type", "command_result")
            put("id", id)
            put("command", command)
            put("success", success)
            put("message", message)
        }.toString()
        webSocket.send(payload)
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
            this,
            0,
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
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager

            val service = NotificationChannel(
                CHANNEL_ID,
                "MeshConnect Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply { description = "Keeps device connected to MeshConnect" }
            nm.createNotificationChannel(service)

            val msg = NotificationChannel(
                MESSAGE_CHANNEL_ID,
                "Messages from Dashboard",
                NotificationManager.IMPORTANCE_HIGH
            ).apply { description = "Notifications sent from MeshConnect dashboard" }
            nm.createNotificationChannel(msg)
        }
    }

    private fun showMessage(title: String, body: String) {
        val pi = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notif = NotificationCompat.Builder(this, MESSAGE_CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setSmallIcon(android.R.drawable.ic_dialog_email)
            .setContentIntent(pi)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(nextMessageNotifId++, notif)
    }
}
