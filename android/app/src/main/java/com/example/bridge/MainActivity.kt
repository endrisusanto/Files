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
import java.net.Socket
import java.security.SecureRandom
import java.util.Base64
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
    @Volatile private var wsSocket: Socket? = null
    @Volatile private var wsConnected = false
    private var lastWsAttempt = 0L
    private val random = SecureRandom()

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
        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 12, 48, 0)
            addView(host)
            addView(share)
        }
        AlertDialog.Builder(this)
            .setTitle("Samba Target")
            .setView(form)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Save & Test") { _, _ ->
                BridgeService.saveTarget(this, host.text.toString(), share.text.toString())
                appendLog("Samba target saved: ${BridgeService.target(this)}")
                refreshStatus("Samba target saved")
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

    private fun sendWebSocketSample(rx: Long, tx: Long) {
        Thread {
            try {
                val socket = wsSocket ?: connectWebSocket()
                val latest = latestFile()?.name ?: "-"
                val text = """{"id":"${json(Build.FINGERPRINT)}","model":"${json(Build.MODEL)}","rx_bps":$rx,"tx_bps":$tx,"samba":"${if (badge.text.contains("ready")) "connected" else "not connected"}","target":"${json(BridgeService.target(this))}","latest":"${json(latest)}"}"""
                writeWebSocketText(socket, text)
                setWebSocketConnected(true)
            } catch (t: Throwable) {
                closeWebSocket()
                setWebSocketConnected(false)
            }
        }.start()
    }

    @Synchronized
    private fun connectWebSocket(): Socket {
        wsSocket?.let { if (it.isConnected && !it.isClosed) return it }
        val now = System.currentTimeMillis()
        check(now - lastWsAttempt > 5_000) { "websocket reconnect backoff" }
        lastWsAttempt = now
        val socket = Socket(BridgeService.host(this), 1421)
        val keyBytes = ByteArray(16).also(random::nextBytes)
        val key = Base64.getEncoder().encodeToString(keyBytes)
        socket.getOutputStream().write(
            "GET /network HTTP/1.1\r\nHost: ${BridgeService.host(this)}:1421\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Key: $key\r\nSec-WebSocket-Version: 13\r\n\r\n"
                .toByteArray()
        )
        val header = StringBuilder()
        while (!header.endsWith("\r\n\r\n")) header.append(socket.getInputStream().read().toChar())
        check(header.startsWith("HTTP/1.1 101")) { "websocket handshake failed" }
        wsSocket = socket
        appendLog("WebSocket connected ws://${BridgeService.host(this)}:1421/network")
        return socket
    }

    private fun writeWebSocketText(socket: Socket, text: String) {
        val data = text.toByteArray()
        val mask = ByteArray(4).also(random::nextBytes)
        val out = socket.getOutputStream()
        out.write(0x81)
        when {
            data.size < 126 -> out.write(0x80 or data.size)
            data.size <= 65_535 -> {
                out.write(0x80 or 126)
                out.write(byteArrayOf((data.size shr 8).toByte(), data.size.toByte()))
            }
            else -> error("websocket payload too large")
        }
        out.write(mask)
        out.write(data.mapIndexed { i, b -> (b.toInt() xor mask[i % 4].toInt()).toByte() }.toByteArray())
        out.flush()
    }

    private fun closeWebSocket() {
        wsSocket?.close()
        wsSocket = null
    }

    private fun setWebSocketConnected(connected: Boolean) {
        if (wsConnected == connected) return
        wsConnected = connected
        runOnUiThread {
            status.text = status.text.toString()
                .replace("WebSocket: connected", "WebSocket: ${if (connected) "connected" else "not connected"}")
                .replace("WebSocket: not connected", "WebSocket: ${if (connected) "connected" else "not connected"}")
        }
    }

    private fun json(value: String) = value.replace("\\", "\\\\").replace("\"", "\\\"")

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
