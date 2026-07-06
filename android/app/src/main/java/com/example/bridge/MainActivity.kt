package com.example.bridge

import android.Manifest
import android.app.Activity
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
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.File
import kotlin.math.max

class MainActivity : Activity() {
    private val tag = "Bridge"
    private val handler = Handler(Looper.getMainLooper())
    private val networkHistory = mutableListOf<Pair<Long, Long>>()
    private val localDir by lazy { File(getExternalFilesDir(null), "SUBRO") }
    private lateinit var badge: TextView
    private lateinit var networkChart: NetworkChartView
    private lateinit var status: TextView
    private var lastRx = 0L
    private var lastTx = 0L

    private val sampleNetwork = object : Runnable {
        override fun run() {
            val rx = TrafficStats.getTotalRxBytes()
            val tx = TrafficStats.getTotalTxBytes()
            if (rx != TrafficStats.UNSUPPORTED.toLong() && tx != TrafficStats.UNSUPPORTED.toLong()) {
                if (lastRx > 0 && lastTx > 0) {
                    networkHistory.add(Pair(max(0L, rx - lastRx), max(0L, tx - lastTx)))
                    if (networkHistory.size > 60) networkHistory.removeAt(0)
                    networkChart.invalidate()
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
        networkChart = NetworkChartView(this)
        val upload = Button(this).apply {
            text = "Upload Latest File"
            setOnClickListener { startLatestUpload() }
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
            addView(refresh, LinearLayout.LayoutParams(-1, -2))
        }

        setContentView(ScrollView(this).apply {
            setBackgroundColor(0xff09090b.toInt())
            addView(content)
        })
        handler.post(sampleNetwork)
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
            refreshStatus("No .tar.md5 file found")
            return
        }
        Log.i(tag, "Starting upload for ${file.absolutePath}")
        val intent = Intent(this, BridgeService::class.java).putExtra("file", file.name)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent) else startService(intent)
        refreshStatus("Uploading ${file.name}")
    }

    private fun refreshStatus(message: String? = null) {
        Log.i(tag, "Refreshing status")
        localDir.mkdirs()
        val file = latestFile()
        status.text = listOfNotNull(
            message,
            "Staging: ${localDir.absolutePath}",
            "Latest: ${file?.name ?: "-"}",
            "Target: ${BridgeService.TARGET}"
        ).joinToString("\n")
        badge.text = "APK: checking Samba"
        badge.setTextColor(0xffd4d4d8.toInt())
        Thread {
            val ok = try {
                BridgeService.checkSamba()
                Log.i(tag, "Samba check ok: ${BridgeService.TARGET}")
                true
            } catch (t: Throwable) {
                Log.e(tag, "Samba check failed: ${BridgeService.TARGET}", t)
                false
            }
            runOnUiThread {
                badge.text = if (ok) "APK: Samba ready" else "APK: Samba unreachable"
                badge.setTextColor(if (ok) 0xff86efac.toInt() else 0xfffca5a5.toInt())
            }
        }.start()
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
