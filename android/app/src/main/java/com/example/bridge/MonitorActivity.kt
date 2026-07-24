package com.example.bridge

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.util.AttributeSet
import android.util.TypedValue
import android.view.*
import android.widget.*
import okhttp3.*
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class NetworkChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#27272a")
        strokeWidth = 2f
        style = Paint.Style.STROKE
    }

    private val linePaints = listOf(
        Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#60a5fa"); strokeWidth = 4f; style = Paint.Style.STROKE },
        Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#f472b6"); strokeWidth = 4f; style = Paint.Style.STROKE },
        Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#a78bfa"); strokeWidth = 4f; style = Paint.Style.STROKE },
        Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#34d399"); strokeWidth = 4f; style = Paint.Style.STROKE },
        Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#fb923c"); strokeWidth = 4f; style = Paint.Style.STROKE }
    )

    private val fillPaints = listOf(
        Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#1e3a8a"); style = Paint.Style.FILL; alpha = 40 },
        Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#831843"); style = Paint.Style.FILL; alpha = 40 },
        Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#4c1d95"); style = Paint.Style.FILL; alpha = 40 },
        Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#064e3b"); style = Paint.Style.FILL; alpha = 40 },
        Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#7c2d12"); style = Paint.Style.FILL; alpha = 40 }
    )

    var devicesData: JSONArray? = null
        set(value) {
            field = value
            postInvalidate()
        }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0 || h <= 0) return

        canvas.drawColor(Color.parseColor("#18181b"))

        for (i in 1..4) {
            val y = (h / 5f) * i
            canvas.drawLine(0f, y, w, y, gridPaint)
        }

        val devices = devicesData ?: return
        if (devices.length() == 0) return

        var maxBps = 1.0
        for (i in 0 until devices.length()) {
            val dev = devices.optJSONObject(i) ?: continue
            val samples = dev.optJSONArray("samples") ?: continue
            for (j in 0 until samples.length()) {
                val s = samples.optJSONObject(j) ?: continue
                val tx = s.optDouble("tx_bps", 0.0)
                if (tx > maxBps) maxBps = tx
            }
        }

        for (i in 0 until devices.length()) {
            val dev = devices.optJSONObject(i) ?: continue
            val samples = dev.optJSONArray("samples") ?: continue
            if (samples.length() < 2) continue

            val strokePaint = linePaints[i % linePaints.size]
            val fillPaint = fillPaints[i % fillPaints.size]

            val linePath = Path()
            val fillPath = Path()

            val sampleCount = Math.min(samples.length(), 60)
            val startIndex = samples.length() - sampleCount

            var firstX = 0f
            var lastX = 0f

            for (j in 0 until sampleCount) {
                val s = samples.optJSONObject(startIndex + j) ?: continue
                val tx = s.optDouble("tx_bps", 0.0)
                val x = j * w / (sampleCount - 1).toFloat()
                val y = h - ((tx / maxBps) * (h * 0.85f)).toFloat()

                if (j == 0) {
                    linePath.moveTo(x, y)
                    fillPath.moveTo(x, y)
                    firstX = x
                } else {
                    linePath.lineTo(x, y)
                    fillPath.lineTo(x, y)
                }
                lastX = x
            }

            fillPath.lineTo(lastX, h)
            fillPath.lineTo(firstX, h)
            fillPath.close()

            canvas.drawPath(fillPath, fillPaint)
            canvas.drawPath(linePath, strokePaint)
        }
    }
}

class MonitorActivity : Activity() {

    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    private var webSocket: WebSocket? = null
    private val detailsState = mutableMapOf<String, Boolean>()

    private var serverUrl = "wss://files.endrisusanto.my.id/"
    private var isWebOnline = false

    private lateinit var chartView: NetworkChartView
    private lateinit var tauriContainer: LinearLayout
    private lateinit var androidContainer: LinearLayout

    private var lastDevicesJson: JSONArray? = null
    private var lastTauriJson: JSONArray? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        enableFullscreenMode()
        super.onCreate(savedInstanceState)

        val root = ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#09090b"))
            isFillViewport = true
        }

        val mainLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        root.addView(mainLayout)

        // Header Title with Settings Gear Icon ⚙
        val headerLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(16))
        }

        val headerTextContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val titleText = TextView(this).apply {
            text = "FireFiles Monitor"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            setTextColor(Color.parseColor("#f97316"))
            setTypeface(null, Typeface.BOLD)
        }
        val subtitleText = TextView(this).apply {
            text = "Realtime WebSocket Staging Dashboard"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(Color.parseColor("#a1a1aa"))
        }
        headerTextContainer.addView(titleText)
        headerTextContainer.addView(subtitleText)

        val settingsGearBtn = TextView(this).apply {
            text = "⚙"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            setTextColor(Color.parseColor("#a1a1aa"))
            setPadding(dp(8), dp(4), dp(8), dp(4))
            setOnClickListener { showSettingsDialog() }
        }

        headerLayout.addView(headerTextContainer)
        headerLayout.addView(settingsGearBtn)
        mainLayout.addView(headerLayout)

        // Realtime Network Traffic Chart Header & Widget
        val chartHeader = TextView(this).apply {
            text = "Realtime Network Traffic"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setTextColor(Color.WHITE)
            setTypeface(null, Typeface.BOLD)
            setPadding(0, dp(8), 0, dp(8))
        }
        mainLayout.addView(chartHeader)

        chartView = NetworkChartView(this).apply {
            background = createCardDrawable("#18181b", "#27272a")
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(150)).apply {
                bottomMargin = dp(16)
            }
        }
        mainLayout.addView(chartView)

        // Tauri Section Header
        val tauriHeader = TextView(this).apply {
            text = "Tauri Staging Hosts"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setTextColor(Color.WHITE)
            setTypeface(null, Typeface.BOLD)
            setPadding(0, dp(8), 0, dp(8))
        }
        mainLayout.addView(tauriHeader)

        tauriContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        mainLayout.addView(tauriContainer)

        // Android Section Header
        val androidHeader = TextView(this).apply {
            text = "Android Devices"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setTextColor(Color.WHITE)
            setTypeface(null, Typeface.BOLD)
            setPadding(0, dp(16), 0, dp(8))
        }
        mainLayout.addView(androidHeader)

        androidContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        mainLayout.addView(androidContainer)

        setContentView(root)

        requestBatteryOptimizationExemption()
        connectWebSocket()
    }

    private fun enableFullscreenMode() {
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.let { controller ->
                controller.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                controller.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            )
        }
    }

    private fun requestBatteryOptimizationExemption() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:$packageName")
                }
                startActivity(intent)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun showSettingsDialog() {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(16))
            setBackgroundColor(Color.parseColor("#18181b"))
        }

        val statusLabel = TextView(this).apply {
            text = "Status: "
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        }
        val badge = TextView(this).apply {
            text = if (isWebOnline) "Web Online" else "Web Offline"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            setTypeface(null, Typeface.BOLD)
            setPadding(dp(8), dp(4), dp(8), dp(4))
            if (isWebOnline) {
                setTextColor(Color.parseColor("#4ade80"))
                background = createBadgeDrawable("#052e16", "#166534")
            } else {
                setTextColor(Color.parseColor("#f87171"))
                background = createBadgeDrawable("#450a0a", "#991b1b")
            }
        }
        val statusRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(12))
            addView(statusLabel)
            addView(badge)
        }

        val inputLabel = TextView(this).apply {
            text = "WSS Server Endpoint:"
            setTextColor(Color.parseColor("#a1a1aa"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setPadding(0, 0, 0, dp(4))
        }

        val input = EditText(this).apply {
            setText(serverUrl)
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            background = createCardDrawable("#09090b", "#27272a")
            setPadding(dp(10), dp(8), dp(10), dp(8))
        }

        layout.addView(statusRow)
        layout.addView(inputLabel)
        layout.addView(input)

        AlertDialog.Builder(this)
            .setTitle("Monitor Settings")
            .setView(layout)
            .setPositiveButton("Connect / Save") { _, _ ->
                serverUrl = input.text.toString().trim()
                connectWebSocket()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun connectWebSocket() {
        webSocket?.close(1000, "Reconnecting")
        var url = serverUrl.trim()
        if (!url.startsWith("ws://") && !url.startsWith("wss://")) {
            url = "wss://$url"
        }
        if (!url.endsWith("/")) {
            url = "$url/"
        }

        val request = Request.Builder().url(url).build()
        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                runOnUiThread {
                    isWebOnline = true
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val json = JSONObject(text)
                    if (json.optString("type") == "state") {
                        val devices = json.optJSONArray("devices")
                        val tauri = json.optJSONArray("tauri")
                        runOnUiThread {
                            lastDevicesJson = devices
                            lastTauriJson = tauri
                            renderUI()
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                runOnUiThread {
                    isWebOnline = false
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                runOnUiThread {
                    isWebOnline = false
                }
            }
        })
    }

    private fun renderUI() {
        chartView.devicesData = lastDevicesJson
        renderTauriHosts(lastTauriJson)
        renderAndroidDevices(lastDevicesJson)

        // Instant update Home Screen Widgets with caching
        ChartWidgetProvider.updateAll(this, lastDevicesJson)
        StagingWidgetProvider.updateAll(this, lastTauriJson)
    }

    private fun renderTauriHosts(array: JSONArray?) {
        tauriContainer.removeAllViews()
        if (array == null || array.length() == 0) {
            val empty = TextView(this).apply {
                text = "No Active Tauri Staging Hosts."
                setTextColor(Color.parseColor("#71717a"))
                setPadding(dp(12), dp(12), dp(12), dp(12))
                background = createCardDrawable("#18181b", "#27272a")
            }
            tauriContainer.addView(empty)
            return
        }

        for (i in 0 until array.length()) {
            val host = array.optJSONObject(i) ?: continue
            val hostId = host.optString("host", host.optString("id", "host"))
            val key = "tauri-$hostId"
            val lastSeen = host.optLong("last_seen", 0)
            val isOnline = (System.currentTimeMillis() - lastSeen) < 15000

            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = createCardDrawable("#18181b", "#27272a")
                setPadding(dp(14), dp(14), dp(14), dp(14))
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    bottomMargin = dp(12)
                }
            }

            val titleLayout = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val name = TextView(this).apply {
                text = hostId
                setTypeface(null, Typeface.BOLD)
                setTextColor(Color.WHITE)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            val badge = TextView(this).apply {
                text = if (isOnline) "Online" else "Offline"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
                setPadding(dp(6), dp(2), dp(6), dp(2))
                if (isOnline) {
                    setTextColor(Color.parseColor("#4ade80"))
                    background = createBadgeDrawable("#052e16", "#166534")
                } else {
                    setTextColor(Color.parseColor("#f87171"))
                    background = createBadgeDrawable("#450a0a", "#991b1b")
                }
            }
            titleLayout.addView(name)
            titleLayout.addView(badge)
            card.addView(titleLayout)

            val isOpen = detailsState[key] == true
            val toggleBtn = TextView(this).apply {
                text = if (isOpen) "▼ Hide Device Info" else "▶ Show Device Info"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                setTextColor(Color.parseColor("#a1a1aa"))
                setPadding(0, dp(8), 0, dp(4))
                setOnClickListener {
                    detailsState[key] = !(detailsState[key] ?: false)
                    renderUI()
                }
            }
            card.addView(toggleBtn)

            if (isOpen) {
                val kvLayout = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(0, dp(4), 0, dp(8))
                }
                kvLayout.addView(createKvRow("Platform", host.optString("platform", "-")))
                kvLayout.addView(createKvRow("Local Source", host.optString("source_dir", "-")))
                kvLayout.addView(createKvRow("Samba Target", host.optString("samba_dir", "-")))
                val devicesCount = host.optJSONArray("devices")?.length() ?: 0
                kvLayout.addView(createKvRow("USB Devices", "$devicesCount Devices"))
                card.addView(kvLayout)
            }

            val files = host.optJSONArray("files")
            val filesTitle = TextView(this).apply {
                text = "STAGING FILES (${files?.length() ?: 0})"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
                setTypeface(null, Typeface.BOLD)
                setTextColor(Color.parseColor("#a1a1aa"))
                setPadding(0, dp(8), 0, dp(4))
            }
            card.addView(filesTitle)

            if (files != null && files.length() > 0) {
                val filesContainer = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    background = createCardDrawable("#0c0c0e", "#1f1f23")
                    setPadding(dp(8), dp(4), dp(8), dp(4))
                }
                for (j in 0 until files.length()) {
                    val f = files.optJSONObject(j) ?: continue
                    val fName = f.optString("name", "-")
                    val fSize = f.optLong("size", 0)
                    val fStatus = f.optString("status", "-")
                    val fileGb = String.format("%.2f GB", fSize.toDouble() / (1024 * 1024 * 1024))

                    val row = LinearLayout(this).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        setPadding(0, dp(4), 0, dp(4))
                    }
                    val fileNameTv = TextView(this).apply {
                        text = fName
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                        setTextColor(Color.WHITE)
                        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    }
                    val fileSizeTv = TextView(this).apply {
                        text = fileGb
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
                        setTextColor(Color.parseColor("#a1a1aa"))
                        setPadding(dp(8), 0, dp(8), 0)
                    }
                    val fileStatusTv = TextView(this).apply {
                        text = fStatus
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 9f)
                        setPadding(dp(4), dp(1), dp(4), dp(1))
                        if (fStatus == "Transfer Complete") {
                            setTextColor(Color.parseColor("#4ade80"))
                            background = createBadgeDrawable("#052e16", "#166534")
                        } else {
                            setTextColor(Color.parseColor("#fde047"))
                            background = createBadgeDrawable("#422006", "#854d0e")
                        }
                    }
                    row.addView(fileNameTv)
                    row.addView(fileSizeTv)
                    row.addView(fileStatusTv)
                    filesContainer.addView(row)
                }
                card.addView(filesContainer)
            } else {
                val noFiles = TextView(this).apply {
                    text = "No Files In Staging Folder."
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                    setTextColor(Color.parseColor("#71717a"))
                }
                card.addView(noFiles)
            }

            tauriContainer.addView(card)
        }
    }

    private fun renderAndroidDevices(array: JSONArray?) {
        androidContainer.removeAllViews()
        if (array == null || array.length() == 0) {
            val empty = TextView(this).apply {
                text = "No Active Android Bridge Devices."
                setTextColor(Color.parseColor("#71717a"))
                setPadding(dp(12), dp(12), dp(12), dp(12))
                background = createCardDrawable("#18181b", "#27272a")
            }
            androidContainer.addView(empty)
            return
        }

        for (i in 0 until array.length()) {
            val d = array.optJSONObject(i) ?: continue
            val id = d.optString("model", d.optString("id", "device"))
            val key = "android-$id"
            val lastSeen = d.optLong("last_seen", 0)
            val isOnline = (System.currentTimeMillis() - lastSeen) < 6000

            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = createCardDrawable("#18181b", "#27272a")
                setPadding(dp(14), dp(14), dp(14), dp(14))
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    bottomMargin = dp(12)
                }
            }

            val titleLayout = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val name = TextView(this).apply {
                text = id
                setTypeface(null, Typeface.BOLD)
                setTextColor(Color.WHITE)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            val badge = TextView(this).apply {
                text = if (isOnline) "Online" else "Offline"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
                setPadding(dp(6), dp(2), dp(6), dp(2))
                if (isOnline) {
                    setTextColor(Color.parseColor("#4ade80"))
                    background = createBadgeDrawable("#052e16", "#166534")
                } else {
                    setTextColor(Color.parseColor("#f87171"))
                    background = createBadgeDrawable("#450a0a", "#991b1b")
                }
            }
            titleLayout.addView(name)
            titleLayout.addView(badge)
            card.addView(titleLayout)

            val isOpen = detailsState[key] == true
            val toggleBtn = TextView(this).apply {
                text = if (isOpen) "▼ Hide Device Info" else "▶ Show Device Info"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                setTextColor(Color.parseColor("#a1a1aa"))
                setPadding(0, dp(8), 0, dp(4))
                setOnClickListener {
                    detailsState[key] = !(detailsState[key] ?: false)
                    renderUI()
                }
            }
            card.addView(toggleBtn)

            if (isOpen) {
                val kvLayout = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(0, dp(4), 0, dp(4))
                }
                val samba = d.optString("samba", "disconnected")
                kvLayout.addView(createKvRow("Samba Status", if (samba == "connected") "Connected" else "Disconnected"))
                kvLayout.addView(createKvRow("Samba Target", d.optString("target", "-")))
                kvLayout.addView(createKvRow("Latest File", d.optString("latest", "-")))

                val samples = d.optJSONArray("samples")
                val lastTx = if (samples != null && samples.length() > 0) {
                    samples.optJSONObject(samples.length() - 1)?.optDouble("tx_bps", 0.0) ?: 0.0
                } else 0.0
                val speedMb = String.format("%.2f MB/s", lastTx / (1024 * 1024))
                kvLayout.addView(createKvRow("Upload Speed", "⬆ $speedMb"))

                card.addView(kvLayout)
            }

            androidContainer.addView(card)
        }
    }

    private fun createKvRow(label: String, value: String): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(2), 0, dp(2))
            val labelTv = TextView(this@MonitorActivity).apply {
                text = label
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                setTextColor(Color.parseColor("#a1a1aa"))
                layoutParams = LinearLayout.LayoutParams(dp(100), ViewGroup.LayoutParams.WRAP_CONTENT)
            }
            val valueTv = TextView(this@MonitorActivity).apply {
                text = value
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                setTextColor(Color.parseColor("#e4e4e7"))
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            addView(labelTv)
            addView(valueTv)
        }
    }

    private fun createCardDrawable(bgColor: String, borderColor: String): GradientDrawable {
        return GradientDrawable().apply {
            setColor(Color.parseColor(bgColor))
            setStroke(dp(1), Color.parseColor(borderColor))
            cornerRadius = dp(8).toFloat()
        }
    }

    private fun createBadgeDrawable(bgColor: String, borderColor: String): GradientDrawable {
        return GradientDrawable().apply {
            setColor(Color.parseColor(bgColor))
            setStroke(dp(1), Color.parseColor(borderColor))
            cornerRadius = dp(999).toFloat()
        }
    }

    private fun dp(valPx: Int): Int {
        return (valPx * resources.displayMetrics.density).toInt()
    }

    override fun onDestroy() {
        super.onDestroy()
        webSocket?.close(1000, "Activity Destroyed")
    }
}
