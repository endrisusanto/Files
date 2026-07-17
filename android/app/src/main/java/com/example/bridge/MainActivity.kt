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
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
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

    private fun styleButton(button: Button, isPrimary: Boolean) {
        val gd = GradientDrawable().apply {
            if (isPrimary) {
                setColor(0xff2563eb.toInt())
                setStroke(2, 0xff3b82f6.toInt())
            } else {
                setColor(0xff27272a.toInt())
                setStroke(2, 0xff3f3f46.toInt())
            }
            cornerRadius = 16f
        }
        button.background = gd
        button.setTextColor(Color.WHITE)
        button.textSize = 12f
        button.transformationMethod = null
        button.setPadding(24, 20, 24, 20)
    }

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
        // Request no title bar feature to prevent native Android ActionBar overlapping
        requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        Log.i(tag, "MainActivity onCreate")
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            Log.i(tag, "Requesting notification permission")
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }

        badge = TextView(this).apply {
            text = "APK: Checking"
            textSize = 13f
            setTextColor(0xff86efac.toInt())
            val bg = GradientDrawable().apply {
                setColor(0xff27272a.toInt())
                cornerRadius = 999f
                setStroke(2, 0xff3f3f46.toInt())
            }
            background = bg
            setPadding(32, 10, 32, 10)
        }
        status = TextView(this).apply {
            textSize = 13f
            setLineSpacing(8f, 1.0f)
            setTextColor(0xffa1a1aa.toInt())
        }
        debugLog = TextView(this).apply {
            textSize = 11f
            setLineSpacing(4f, 1.0f)
            setTextColor(0xffa1a1aa.toInt())
        }
        networkChart = NetworkChartView(this)

        val upload = Button(this).apply {
            text = "Upload All Files"
            setOnClickListener { startAllUpload() }
            styleButton(this, true)
        }
        val testSamba = Button(this).apply {
            text = "Test Upload"
            setOnClickListener { testUpload() }
            styleButton(this, false)
        }
        val settings = Button(this).apply {
            text = "Samba Settings"
            setOnClickListener { showSambaSettings() }
            styleButton(this, false)
        }
        val refresh = Button(this).apply {
            text = "Refresh"
            setOnClickListener { refreshStatus() }
            styleButton(this, false)
        }

        // Left Panel (Column 1)
        val leftColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.LEFT
            
            addView(TextView(this@MainActivity).apply {
                text = "Android File Bridge"
                textSize = 22f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                setTextColor(0xfffafafa.toInt())
                setPadding(0, 0, 0, 8)
            }, LinearLayout.LayoutParams(-1, -2))

            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.LEFT
                setPadding(0, 0, 0, 16)
                addView(badge)
            }, LinearLayout.LayoutParams(-1, -2))

            // Status Panel Card
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                val bg = GradientDrawable().apply {
                    setColor(0xff18181b.toInt())
                    cornerRadius = 16f
                    setStroke(2, 0xff27272a.toInt())
                }
                background = bg
                setPadding(24, 24, 24, 24)
                addView(status)
            }, LinearLayout.LayoutParams(-1, -2))

            // Buttons 2x2 Grid
            val row1 = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(upload, LinearLayout.LayoutParams(0, -2, 1.0f).apply { rightMargin = 8 })
                addView(testSamba, LinearLayout.LayoutParams(0, -2, 1.0f))
            }
            val row2 = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(settings, LinearLayout.LayoutParams(0, -2, 1.0f).apply { rightMargin = 8 })
                addView(refresh, LinearLayout.LayoutParams(0, -2, 1.0f))
            }
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, 24, 0, 0)
                addView(row1, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = 8 })
                addView(row2, LinearLayout.LayoutParams(-1, -2))
            }, LinearLayout.LayoutParams(-1, -2))
        }

        // Right Panel (Column 2)
        val rightColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.LEFT

            addView(TextView(this@MainActivity).apply {
                text = "Realtime Network Traffic"
                textSize = 14f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                setTextColor(0xfffafafa.toInt())
                setPadding(0, 0, 0, 12)
            }, LinearLayout.LayoutParams(-1, -2))

            addView(networkChart, LinearLayout.LayoutParams(-1, 0, 1.0f).apply {
                bottomMargin = 24
            })

            addView(TextView(this@MainActivity).apply {
                text = "System Log"
                textSize = 14f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                setTextColor(0xfffafafa.toInt())
                setPadding(0, 0, 0, 12)
            }, LinearLayout.LayoutParams(-1, -2))

            addView(ScrollView(this@MainActivity).apply {
                val logBg = GradientDrawable().apply {
                    setColor(0xff18181b.toInt())
                    cornerRadius = 16f
                    setStroke(2, 0xff27272a.toInt())
                }
                background = logBg
                setPadding(20, 20, 20, 20)
                addView(debugLog)
            }, LinearLayout.LayoutParams(-1, 0, 1.0f))
        }

        val rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(40, 40, 40, 40)
            setBackgroundColor(0xff09090b.toInt())
            fitsSystemWindows = false

            val leftParams = LinearLayout.LayoutParams(0, -2, 1.0f).apply {
                rightMargin = 40
            }
            val rightParams = LinearLayout.LayoutParams(0, -1, 1.2f)

            addView(leftColumn, leftParams)
            addView(rightColumn, rightParams)
        }

        setContentView(ScrollView(this).apply {
            fitsSystemWindows = false
            isFillViewport = true
            setBackgroundColor(0xff09090b.toInt())
            addView(rootLayout)
        })

        // ponytail: hide status bar and navigation bar for immersive fullscreen (must be set after contentView)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.let { controller ->
                controller.hide(android.view.WindowInsets.Type.statusBars() or android.view.WindowInsets.Type.navigationBars())
                controller.systemBarsBehavior = android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            )
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            window.statusBarColor = Color.TRANSPARENT
            window.navigationBarColor = Color.TRANSPARENT
        }

        handler.post(sampleNetwork)
        appendLog("APK started")
        refreshStatus()
    }

    override fun onDestroy() {
        handler.removeCallbacks(sampleNetwork)
        closeWebSocket()
        super.onDestroy()
    }

    private fun md5Files(): List<File> =
        localDir.listFiles()
            ?.filter { it.isFile && it.name.endsWith(".md5") }
            ?.sortedBy { it.lastModified() }
            ?: emptyList()

    private fun latestFile(): File? = md5Files().maxByOrNull { it.lastModified() }

    private fun startAllUpload() {
        val files = md5Files()
        if (files.isEmpty()) {
            Log.w(tag, "Upload skipped: no .md5 file")
            appendLog("Upload skipped: no .md5 file")
            refreshStatus("No .md5 file found")
            return
        }
        Log.i(tag, "Starting upload all files count=${files.size}")
        appendLog("Starting upload all files count=${files.size}")
        val intent = Intent(this, BridgeService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent) else startService(intent)
        refreshStatus("Uploading ${files.size} files")
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
        val sambaLine = "Samba Connection: Checking"
        status.text = listOfNotNull(
            message,
            "Staging Directory: ${if (localDir.canWrite()) "Writable" else "Not Writable"} (${localDir.listFiles()?.size ?: 0} Files)",
            sambaLine,
            "Cloud Connection: ${if (wsConnected) "Connected" else "Not Connected"}",
            "Local Path: ${localDir.absolutePath}",
            "Latest File: ${file?.name ?: "-"}",
            "Samba Target: ${BridgeService.target(this)}"
        ).joinToString("\n")
        appendLog("Status refreshed")
        badge.text = "APK: Checking Samba"
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
                badge.text = if (ok) "APK: Samba Ready" else "APK: Samba Unreachable"
                badge.setTextColor(if (ok) 0xff86efac.toInt() else 0xfffca5a5.toInt())
                status.text = status.text.toString().replace(sambaLine, if (ok) "Samba Connection: Connected" else "Samba Connection: Not Connected")
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
                        .replace("Cloud Connection: Not Connected", "Cloud Connection: Connected")
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
                                "upload", "upload_all" -> {
                                    appendLog("Remote command: upload all files")
                                    startAllUpload()
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
                        .replace("Cloud Connection: Connected", "Cloud Connection: Not Connected")
                }
                appendLog("WebSocket failure: ${t.message}")
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                wsConnected = false
                okWebSocket = null
                runOnUiThread {
                    status.text = status.text.toString()
                        .replace("Cloud Connection: Connected", "Cloud Connection: Not Connected")
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
                    put("samba", if (badge.text.contains("ready", ignoreCase = true)) "connected" else "not connected")
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
            val rect = RectF(0f, 0f, width.toFloat(), height.toFloat())
            canvas.drawRoundRect(rect, 16f, 16f, bgPaint)
            val maxBytes = max(1L, networkHistory.flatMap { listOf(it.first, it.second) }.maxOrNull() ?: 1L)
            drawCurve(canvas, true, maxBytes, downPaint)
            drawCurve(canvas, false, maxBytes, upPaint)
            val last = networkHistory.lastOrNull() ?: Pair(0L, 0L)
            canvas.drawText("Down ${mbps(last.first)} MB/s   Up ${mbps(last.second)} MB/s", 24f, 44f, textPaint)
        }

        private fun drawCurve(canvas: Canvas, down: Boolean, maxBytes: Long, paint: Paint) {
            if (networkHistory.size < 2) return
            val path = android.graphics.Path()
            networkHistory.forEachIndexed { index, point ->
                val x = index * width.toFloat() / (networkHistory.size - 1)
                val bytes = if (down) point.first else point.second
                val y = height - (bytes.toFloat() / maxBytes) * height
                if (index == 0) {
                    path.moveTo(x, y)
                } else {
                    val prevX = (index - 1) * width.toFloat() / (networkHistory.size - 1)
                    val prevBytes = if (down) networkHistory[index - 1].first else networkHistory[index - 1].second
                    val prevY = height - (prevBytes.toFloat() / maxBytes) * height
                    
                    // Control points for smooth horizontal bezier flow
                    val cp1x = prevX + (x - prevX) / 2
                    val cp1y = prevY
                    val cp2x = prevX + (x - prevX) / 2
                    val cp2y = y
                    path.cubicTo(cp1x, cp1y, cp2x, cp2y, x, y)
                }
            }
            canvas.drawPath(path, paint)
        }

        private fun mbps(bytes: Long) = "%.2f".format(bytes / 1024.0 / 1024.0)
    }
}
