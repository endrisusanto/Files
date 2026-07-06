package com.example.bridge

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.TrafficStats
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.File
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import kotlin.math.max

class MainActivity : Activity() {
    private val tag = "Bridge"
    private val handler = Handler(Looper.getMainLooper())
    private val networkHistory = mutableListOf<Pair<Long, Long>>()
    private val localDir by lazy { File(getExternalFilesDir(null), "SUBRO") }
    private lateinit var badge: TextView
    private lateinit var debugLog: TextView
    private lateinit var networkChart: NetworkChartView
    private lateinit var status: TextView
    private val debugLines = ArrayDeque<String>()
    private var lastRx = 0L
    private var lastTx = 0L
    private val okHttpClient = OkHttpClient()
    @Volatile private var okWebSocket: WebSocket? = null
    @Volatile private var wsConnected = false
    private var lastWsAttempt = 0L

    private val sampleNetwork = object : Runnable {
        override fun run() {
            val rx = TrafficStats.getTotalRxBytes()
            val tx = TrafficStats.getTotalTxBytes()
            if (rx != TrafficStats.UNSUPPORTED.toLong() && tx != TrafficStats.UNSUPPORTED.toLong()) {
                if (lastRx > 0 && lastTx > 0) {
                    val sample = Pair(max(0L, rx - lastRx), max(0L, tx - lastTx))
                    networkHistory.add(sample)
                    if (networkHistory.size > 60) networkHistory.removeAt(0)
                    networkChart.invalidate()
                    sendWebSocketSample(sample.first, sample.second)
                }
                lastRx = rx
                lastTx = tx
            }
            handler.postDelayed(this, 1_000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.i(tag, "MainActivity onCreate")
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            Log.i(tag, "Requesting notification permission")
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }

        badge = TextView(this).apply {
            text = "APK: checking"
            textSize = 14f
            setTextColor(0xffd4d4d8.toInt())
            setBackgroundColor(0xff27272a.toInt())
            setPadding(24, 12, 24, 12)
        }
        status = TextView(this).apply {
            textSize = 16f
            setLineSpacing(6f, 1.05f)
            setTextColor(0xffd4d4d8.toInt())
        }
        debugLog = TextView(this).apply {
            textSize = 12f
            setLineSpacing(4f, 1.0f)
            setTextColor(0xffd4d4d8.toInt())
            setBackgroundColor(0xff18181b.toInt())
            setPadding(20, 20, 20, 20)
        }
        networkChart = NetworkChartView(this)
        val upload = Button(this).apply {
            text = "Upload Latest File"
            setOnClickListener { startLatestUpload() }
        }
        val testSamba = Button(this).apply {
            text = "Test Upload test.txt"
            setOnClickListener { testUpload() }
        }
        val settings = Button(this).apply {
            text = "Samba Settings"
            setOnClickListener { showSambaSettings() }
        }
        val refresh = Button(this).apply {
            text = "Refresh"
            setOnClickListener { refreshStatus() }
        }

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(40, 56, 40, 56)
            setBackgroundColor(0xff09090b.toInt())
            addView(TextView(this@MainActivity).apply {
                text = "Android File Bridge"
                textSize = 26f
                setTextColor(0xfffafafa.toInt())
                setPadding(0, 0, 0, 32)
            }, LinearLayout.LayoutParams(-1, -2))
            addView(badge, LinearLayout.LayoutParams(-2, -2).apply {
                bottomMargin = 28
            })
            addView(status, LinearLayout.LayoutParams(-1, -2).apply {
                bottomMargin = 36
            })
            addView(networkChart, LinearLayout.LayoutParams(-1, 220).apply {
                bottomMargin = 28
            })
            addView(upload, LinearLayout.LayoutParams(-1, -2).apply {
                bottomMargin = 18
            })
            addView(testSamba, LinearLayout.LayoutParams(-1, -2).apply {
                bottomMargin = 18
            })
            addView(settings, LinearLayout.LayoutParams(-1, -2).apply {
                bottomMargin = 18
            })
            addView(refresh, LinearLayout.LayoutParams(-1, -2))
            addView(TextView(this@MainActivity).apply {
                text = "Debug Log"
                textSize = 18f
                setTextColor(0xfffafafa.toInt())
                setPadding(0, 32, 0, 12)
            }, LinearLayout.LayoutParams(-1, -2))
            addView(ScrollView(this@MainActivity).apply {
                setBackgroundColor(0xff18181b.toInt())
                addView(debugLog)
            }, LinearLayout.LayoutParams(-1, 320))
        }

        setContentView(ScrollView(this).apply {
            setBackgroundColor(0xff09090b.toInt())
            addView(content)
        })
        handler.post(sampleNetwork)
        appendLog("APK started")
        refreshStatus()
    }

    override fun onDestroy() {
        handler.removeCallbacks(sampleNetwork)
        closeWebSocket()
        super.onDestroy()
    }

    private fun latestFile(): File? =
        localDir.listFiles()
            ?.filter { it.isFile && it.name.endsWith(".tar.md5") }
            ?.maxByOrNull { it.lastModified() }

    private fun startLatestUpload() {
        val file = latestFile()
        if (file == null) {
            Log.w(tag, "Upload skipped: no .tar.md5 file")
            appendLog("Upload skipped: no .tar.md5 file")
            refreshStatus("No .tar.md5 file found")
            return
        }
        Log.i(tag, "Starting upload for ${file.absolutePath}")
        appendLog("Starting upload ${file.name}")
        val intent = Intent(this, BridgeService::class.java).putExtra("file", file.name)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent) else startService(intent)
        refreshStatus("Uploading ${file.name}")
    }

    private fun testUpload() {
        appendLog("Testing Samba upload test.txt")
        Thread {
            val message = try {
                BridgeService.uploadTestFile(this)
                "Test upload OK: ${BridgeService.target(this)}test.txt"
            } catch (t: Throwable) {
                Log.e(tag, "Test upload failed", t)
                "Test upload failed: ${t.message}"
            }
            runOnUiThread {
                appendLog(message)
                refreshStatus(message)
            }
        }.start()
    }

    private fun showSambaSettings() {
        val host = EditText(this).apply {
            hint = "Host"
            setText(BridgeService.host(this@MainActivity))
        }
        val share = EditText(this).apply {
            hint = "Share"
            setText(BridgeService.share(this@MainActivity))
        }
        val wsUrl = EditText(this).apply {
            hint = "WebSocket URL"
            setText(getSharedPreferences("bridge", Context.MODE_PRIVATE).getString("ws_url", "wss://files.endrisusanto.my.id/network"))
        }
        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 12, 48, 0)
            addView(host)
            addView(share)
            addView(wsUrl)
        }
        AlertDialog.Builder(this)
            .setTitle("Samba & WebSocket Target")
            .setView(form)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Save & Test") { _, _ ->
                BridgeService.saveTarget(this, host.text.toString(), share.text.toString())
                getSharedPreferences("bridge", Context.MODE_PRIVATE).edit()
                    .putString("ws_url", wsUrl.text.toString().trim())
                    .apply()
                appendLog("Settings saved. Samba: ${BridgeService.target(this)}, WS: ${wsUrl.text}")
                refreshStatus("Settings saved")
                Thread {
                    closeWebSocket()
                    connectWebSocketOk()
                }.start()
                testUpload()
            }
            .show()
    }

    private fun refreshStatus(message: String? = null) {
        Log.i(tag, "Refreshing status")
        localDir.mkdirs()
        val file = latestFile()
        val sambaLine = "Samba: checking"
        status.text = listOfNotNull(
            message,
            "Tauri: staging ${if (localDir.canWrite()) "writable" else "not writable"} (${localDir.listFiles()?.size ?: 0} files)",
            sambaLine,
            "WebSocket: ${if (wsConnected) "connected" else "not connected"}",
            "Staging: ${localDir.absolutePath}",
            "Latest: ${file?.name ?: "-"}",
            "Target: ${BridgeService.target(this)}"
        ).joinToString("\n")
        appendLog("Status refreshed")
        badge.text = "APK: checking Samba"
        badge.setTextColor(0xffd4d4d8.toInt())
        Thread {
            val ok = try {
                BridgeService.checkSamba(this)
                Log.i(tag, "Samba check ok: ${BridgeService.target(this)}")
                appendLog("Samba check OK")
                true
            } catch (t: Throwable) {
                Log.e(tag, "Samba check failed: ${BridgeService.target(this)}", t)
                appendLog("Samba check failed: ${t.message}")
                false
            }
            runOnUiThread {
                badge.text = if (ok) "APK: Samba ready" else "APK: Samba unreachable"
                badge.setTextColor(if (ok) 0xff86efac.toInt() else 0xfffca5a5.toInt())
                status.text = status.text.toString().replace(sambaLine, if (ok) "Samba: connected" else "Samba: not connected")
            }
        }.start()
    }

    @Synchronized
    private fun connectWebSocketOk(): WebSocket {
        okWebSocket?.let { if (wsConnected) return it }
        val now = System.currentTimeMillis()
        if (now - lastWsAttempt < 5000) {
            throw IllegalStateException("websocket reconnect backoff")
        }
        lastWsAttempt = now

        val wsUrl = getSharedPreferences("bridge", Context.MODE_PRIVATE)
            .getString("ws_url", "wss://files.endrisusanto.my.id/network") ?: "wss://files.endrisusanto.my.id/network"

        appendLog("Connecting to WebSocket: $wsUrl")
        val request = Request.Builder().url(wsUrl).build()
        val socket = okHttpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                wsConnected = true
                runOnUiThread {
                    status.text = status.text.toString()
                        .replace("WebSocket: not connected", "WebSocket: connected")
                        .replace("WebSocket: connected", "WebSocket: connected")
                }
                appendLog("WebSocket connected to $wsUrl")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                appendLog("WS Received: $text")
                try {
                    val json = JSONObject(text)
                    if (json.has("command")) {
                        val command = json.getString("command")
                        runOnUiThread {
                            when (command) {
                                "upload" -> {
                                    appendLog("Remote command: upload latest file")
                                    startLatestUpload()
                                }
                                "refresh" -> {
                                    appendLog("Remote command: refresh")
                                    refreshStatus("Remote refresh triggered")
                                }
                                "settings" -> {
                                    val host = json.optString("host")
                                    val share = json.optString("share")
                                    if (host.isNotEmpty() && share.isNotEmpty()) {
                                        BridgeService.saveTarget(this@MainActivity, host, share)
                                        appendLog("Remote settings saved: Host=$host, Share=$share")
                                        refreshStatus("Remote settings applied")
                                        testUpload()
                                    }
                                }
                                else -> appendLog("Unknown remote command: $command")
                            }
                        }
                    }
                } catch (e: Exception) {
                    appendLog("Error parsing WS message: ${e.message}")
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                wsConnected = false
                okWebSocket = null
                runOnUiThread {
                    status.text = status.text.toString()
                        .replace("WebSocket: connected", "WebSocket: not connected")
                        .replace("WebSocket: not connected", "WebSocket: not connected")
                }
                appendLog("WebSocket failure: ${t.message}")
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                wsConnected = false
                okWebSocket = null
                runOnUiThread {
                    status.text = status.text.toString()
                        .replace("WebSocket: connected", "WebSocket: not connected")
                        .replace("WebSocket: not connected", "WebSocket: not connected")
                }
                appendLog("WebSocket closed: $reason")
            }
        })
        okWebSocket = socket
        return socket
    }

    private fun sendWebSocketSample(rx: Long, tx: Long) {
        Thread {
            try {
                val socket = okWebSocket ?: connectWebSocketOk()
                if (!wsConnected) {
                    return@Thread
                }
                val latest = latestFile()?.name ?: "-"
                val sampleObj = JSONObject().apply {
                    put("id", Build.FINGERPRINT)
                    put("model", Build.MODEL)
                    put("rx_bps", rx)
                    put("tx_bps", tx)
                    put("samba", if (badge.text.contains("ready")) "connected" else "not connected")
                    put("target", BridgeService.target(this@MainActivity))
                    put("latest", latest)
                    put("current_file", BridgeService.currentFile)
                    put("upload_percent", BridgeService.currentProgress)
                    put("queue_success", BridgeService.queueSuccess)
                    put("queue_total", BridgeService.queueTotal)
                }
                socket.send(sampleObj.toString())
            } catch (t: Throwable) {
                closeWebSocket()
                wsConnected = false
                appendLog("WS send failed: ${t.message}")
            }
        }.start()
    }

    private fun closeWebSocket() {
        okWebSocket?.close(1000, "App closed/changed")
        okWebSocket = null
        wsConnected = false
    }

    private fun appendLog(line: String) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            runOnUiThread { appendLog(line) }
            return
        }
        Log.i(tag, line)
        debugLines.addFirst("${java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date())} $line")
        while (debugLines.size > 80) debugLines.removeLast()
        if (::debugLog.isInitialized) debugLog.text = debugLines.joinToString("\n")
    }

    inner class NetworkChartView(context: Context) : View(context) {
        private val downPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(134, 239, 172)
            strokeWidth = 4f
            style = Paint.Style.STROKE
        }
        private val upPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(147, 197, 253)
            strokeWidth = 4f
            style = Paint.Style.STROKE
        }
        private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xffd4d4d8.toInt()
            textSize = 30f
        }
        private val bgPaint = Paint().apply {
            color = 0xff18181b.toInt()
        }

        override fun onDraw(canvas: Canvas) {
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)
            val maxBytes = max(1L, networkHistory.flatMap { listOf(it.first, it.second) }.maxOrNull() ?: 1L)
            drawLine(canvas, true, maxBytes, downPaint)
            drawLine(canvas, false, maxBytes, upPaint)
            val last = networkHistory.lastOrNull() ?: Pair(0L, 0L)
            canvas.drawText("Down ${mbps(last.first)} MB/s   Up ${mbps(last.second)} MB/s", 16f, 38f, textPaint)
        }

        private fun drawLine(canvas: Canvas, down: Boolean, maxBytes: Long, paint: Paint) {
            if (networkHistory.size < 2) return
            var previousX = 0f
            var previousY = height.toFloat()
            networkHistory.forEachIndexed { index, point ->
                val x = index * width.toFloat() / (networkHistory.size - 1)
                val bytes = if (down) point.first else point.second
                val y = height - (bytes.toFloat() / maxBytes) * height
                if (index > 0) canvas.drawLine(previousX, previousY, x, y, paint)
                previousX = x
                previousY = y
            }
        }

        private fun mbps(bytes: Long) = "%.2f".format(bytes / 1024.0 / 1024.0)
    }
}
