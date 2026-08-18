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
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
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
    private val networkHistory = mutableListOf<Triple<Long, Long, Long>>()
    private val localDir by lazy { File(getExternalFilesDir(null), "SUBRO") }
    private lateinit var badge: TextView
    private lateinit var debugLog: TextView
    private lateinit var networkChart: NetworkChartView
    private lateinit var status: TextView
    private var activeTab = 0
    private lateinit var logsContainer: ScrollView
    private lateinit var progressScrollView: ScrollView
    private lateinit var progressContainer: LinearLayout
    private lateinit var tabLogsBtn: TextView
    private lateinit var tabProgressBtn: TextView
    private lateinit var rootLayout: LinearLayout
    private lateinit var leftColumn: LinearLayout
    private lateinit var rightColumn: LinearLayout
    private lateinit var leftExpandBar: LinearLayout
    private lateinit var collapseBtn: TextView
    private lateinit var chartAccordionBtn: TextView
    private lateinit var leftDetailsContainer: LinearLayout
    private lateinit var titleRow: LinearLayout
    private var isMinimized = false
    private var isChartExpanded = true
    private var wasTransferring = false
    private lateinit var confettiView: ConfettiView
    private var lastTauriFiles: org.json.JSONArray? = null
    private val debugLines = ArrayDeque<String>()
    private val transferProgressMap = java.util.concurrent.ConcurrentHashMap<String, Int>()
    private val fileExpectedSizeMap = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private val transferLastSizeMap = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private val transferLastTimeMap = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private val transferSpeedMap = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val adbPushSpeedMap = java.util.concurrent.ConcurrentHashMap<String, Long>()
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
            if (lastRx > 0 && lastTx > 0) {
                val adbSpeed = adbPushSpeedMap.values.sum()
                val sample = Triple(max(0L, rx - lastRx), max(0L, tx - lastTx), adbSpeed)
                networkHistory.add(sample)
                if (networkHistory.size > 60) networkHistory.removeAt(0)
                networkChart.invalidate()
                sendWebSocketSample(sample.first, sample.second)
            }
            lastRx = rx
            lastTx = tx
            
            if (activeTab == 1) {
                val files = md5Files()
                val now = System.currentTimeMillis()
                for (file in files) {
                    var expectedSize = fileExpectedSizeMap[file.name] ?: 0L
                    if (expectedSize == 0L) {
                        val metaFile = File(file.parentFile, file.name + ".meta")
                        if (metaFile.exists()) {
                            try {
                                val size = metaFile.readText().trim().toLong()
                                if (size > 0L) {
                                    fileExpectedSizeMap[file.name] = size
                                    expectedSize = size
                                }
                            } catch (e: Exception) {}
                        }
                    }
                    val currentSize = file.length()
                    val isFinished = (expectedSize > 0L && currentSize >= expectedSize) || (transferProgressMap[file.name] ?: 0 >= 100)
                    
                    if (isFinished) {
                        transferSpeedMap.remove(file.name)
                        transferLastSizeMap.remove(file.name)
                        transferLastTimeMap.remove(file.name)
                    } else {
                        val lastSize = transferLastSizeMap[file.name] ?: 0L
                        val lastTime = transferLastTimeMap[file.name] ?: 0L
                        if (lastTime > 0L && now > lastTime && currentSize > lastSize) {
                            val bytesPerSec = ((currentSize - lastSize) * 1000.0) / (now - lastTime)
                            val mbPerSec = bytesPerSec / (1024.0 * 1024.0)
                            if (mbPerSec >= 0.01) {
                                transferSpeedMap[file.name] = " . %.2f MB/s".format(mbPerSec)
                            } else {
                                transferSpeedMap.remove(file.name)
                            }
                        }
                        transferLastSizeMap[file.name] = currentSize
                        transferLastTimeMap[file.name] = now
                    }
                }
                updateProgressList()
            }
            handler.postDelayed(this, 1_000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Keep screen bright while active
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
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
        leftColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.LEFT
            
            leftDetailsContainer = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                
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

            titleRow = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                
                val titleTv = TextView(this@MainActivity).apply {
                    text = "Android File Bridge"
                    textSize = 20f
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                    setTextColor(0xfffafafa.toInt())
                    setOnClickListener {
                        val isLandscape = resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
                        if (!isLandscape) {
                            if (leftDetailsContainer.visibility == View.VISIBLE) {
                                leftDetailsContainer.visibility = View.GONE
                                isMinimized = true
                            } else {
                                leftDetailsContainer.visibility = View.VISIBLE
                                isMinimized = false
                            }
                        }
                    }
                }
                
                collapseBtn = TextView(this@MainActivity).apply {
                    text = "Minimize"
                    textSize = 12f
                    setTextColor(0xff3b82f6.toInt())
                    setPadding(16, 8, 16, 8)
                    setOnClickListener {
                        leftColumn.visibility = View.GONE
                        leftExpandBar.visibility = View.VISIBLE
                        isMinimized = true
                    }
                }
                
                addView(titleTv, LinearLayout.LayoutParams(0, -2, 1.0f))
                addView(collapseBtn, LinearLayout.LayoutParams(-2, -2))
            }
            
            addView(titleRow, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = 8 })
            addView(leftDetailsContainer, LinearLayout.LayoutParams(-1, -2))
        }

        // Right Panel (Column 2)
        rightColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.LEFT
            
            // Accordion Header for Realtime Network Traffic
            val chartHeader = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, 0, 0, dpToPx(8))
                isClickable = true
                isFocusable = true
                
                val chartTitle = TextView(this@MainActivity).apply {
                    text = "Realtime Network Traffic"
                    textSize = 14f
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                    setTextColor(0xfffafafa.toInt())
                }
                
                chartAccordionBtn = TextView(this@MainActivity).apply {
                    text = if (isChartExpanded) "▲ Collapse" else "▼ Expand"
                    textSize = 11f
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                    setTextColor(0xffa1a1aa.toInt())
                    setPadding(dpToPx(8), dpToPx(4), dpToPx(8), dpToPx(4))
                }
                
                addView(chartTitle, LinearLayout.LayoutParams(0, -2, 1.0f))
                addView(chartAccordionBtn, LinearLayout.LayoutParams(-2, -2))
                
                setOnClickListener {
                    isChartExpanded = !isChartExpanded
                    chartAccordionBtn.text = if (isChartExpanded) "▲ Collapse" else "▼ Expand"
                    networkChart.visibility = if (isChartExpanded) View.VISIBLE else View.GONE
                }
            }
            addView(chartHeader, LinearLayout.LayoutParams(-1, -2))

            addView(networkChart, LinearLayout.LayoutParams(-1, 0, 1.0f).apply {
                bottomMargin = 24
            })

            // Tab bar
            val tabLayout = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, 0, 0, 16)
                
                tabLogsBtn = TextView(this@MainActivity).apply {
                    text = "System Logs"
                    setOnClickListener {
                        activeTab = 0
                        updateTabVisibility()
                    }
                }
                
                tabProgressBtn = TextView(this@MainActivity).apply {
                    text = "Transfer Progress"
                    setOnClickListener {
                        activeTab = 1
                        updateTabVisibility()
                    }
                }
                
                styleTabButton(tabLogsBtn, true)
                styleTabButton(tabProgressBtn, false)
                
                addView(tabLogsBtn, LinearLayout.LayoutParams(0, -2, 1.0f).apply { rightMargin = 8 })
                addView(tabProgressBtn, LinearLayout.LayoutParams(0, -2, 1.0f))
            }
            addView(tabLayout, LinearLayout.LayoutParams(-1, -2))

            val contentFrame = FrameLayout(this@MainActivity).apply {
                layoutParams = LinearLayout.LayoutParams(-1, 0, 1.0f)
            }

            logsContainer = ScrollView(this@MainActivity).apply {
                val logBg = GradientDrawable().apply {
                    setColor(0xff18181b.toInt())
                    cornerRadius = 16f
                    setStroke(2, 0xff27272a.toInt())
                }
                background = logBg
                setPadding(20, 20, 20, 20)
                addView(debugLog)
                visibility = View.VISIBLE
            }

            progressScrollView = ScrollView(this@MainActivity).apply {
                val progressBg = GradientDrawable().apply {
                    setColor(0xff18181b.toInt())
                    cornerRadius = 16f
                    setStroke(2, 0xff27272a.toInt())
                }
                background = progressBg
                setPadding(20, 20, 20, 20)
                
                progressContainer = LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = ViewGroup.LayoutParams(-1, -2)
                }
                addView(progressContainer)
                visibility = View.GONE
            }

            contentFrame.addView(logsContainer, FrameLayout.LayoutParams(-1, -1))
            contentFrame.addView(progressScrollView, FrameLayout.LayoutParams(-1, -1))
            addView(contentFrame)
        }

        leftExpandBar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(0xff18181b.toInt())
            val gd = GradientDrawable().apply {
                setColor(0xff18181b.toInt())
                setStroke(2, 0xff27272a.toInt())
            }
            background = gd
            setPadding(0, 0, 0, 0)
            visibility = View.GONE
            
            val batteryView = BatteryProgressView(this@MainActivity).apply {
                layoutParams = LinearLayout.LayoutParams(-1, -1)
            }
            addView(batteryView)
            
            setOnClickListener {
                leftColumn.visibility = View.VISIBLE
                leftExpandBar.visibility = View.GONE
                isMinimized = false
            }
        }

        rootLayout = LinearLayout(this).apply {
            setBackgroundColor(0xff09090b.toInt())
            fitsSystemWindows = false
        }

        adjustOrientationLayout()
        setupSwipeGestures()

        confettiView = ConfettiView(this).apply {
            visibility = View.GONE
        }

        val mainContainer = FrameLayout(this).apply {
            addView(rootLayout, FrameLayout.LayoutParams(-1, -1))
            addView(confettiView, FrameLayout.LayoutParams(-1, -1))
        }

        setContentView(mainContainer)

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

    override fun dispatchTouchEvent(ev: MotionEvent?): Boolean {
        if (ev?.action == MotionEvent.ACTION_DOWN) {
            if (::confettiView.isInitialized && confettiView.isRunning()) {
                confettiView.stopConfetti()
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    private fun md5Files(): List<File> =
        localDir.listFiles()
            ?.filter { it.isFile && it.name.endsWith(".md5") }
            ?.sortedBy { it.lastModified() }
            ?: emptyList()

    private fun latestFile(): File? = md5Files().maxByOrNull { it.lastModified() }

    private fun startAllUpload() {
        if (::confettiView.isInitialized && confettiView.isRunning()) {
            confettiView.stopConfetti()
        }
        val files = md5Files()
        if (files.isEmpty()) {
            Log.w(tag, "Upload skipped: no .md5 file")
            appendLog("Upload skipped: no .md5 file")
            refreshStatus("No .md5 file found")
            return
        }
        wasTransferring = true
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

    private fun styleTabButton(button: TextView, isActive: Boolean) {
        val gd = GradientDrawable().apply {
            if (isActive) {
                setColor(0xff2563eb.toInt())
                setStroke(2, 0xff3b82f6.toInt())
            } else {
                setColor(0xff18181b.toInt())
                setStroke(2, 0xff27272a.toInt())
            }
            cornerRadius = 16f
        }
        button.background = gd
        button.setTextColor(Color.WHITE)
        button.textSize = 12f
        button.gravity = Gravity.CENTER
        button.setPadding(24, 20, 24, 20)
    }

    private fun updateTabVisibility() {
        if (activeTab == 0) {
            logsContainer.visibility = View.VISIBLE
            progressScrollView.visibility = View.GONE
            styleTabButton(tabLogsBtn, true)
            styleTabButton(tabProgressBtn, false)
        } else {
            logsContainer.visibility = View.GONE
            progressScrollView.visibility = View.VISIBLE
            styleTabButton(tabLogsBtn, false)
            styleTabButton(tabProgressBtn, true)
            updateProgressList()
        }
    }

    private fun dpToPx(dp: Int): Int {
        val density = resources.displayMetrics.density
        return (dp * density).toInt()
    }

    private fun formatFileSize(size: Long): String {
        val kb = size / 1024.0
        val mb = kb / 1024.0
        val gb = mb / 1024.0
        return when {
            gb >= 1.0 -> "%.2f GB".format(gb)
            mb >= 1.0 -> "%.2f MB".format(mb)
            else -> "%.2f KB".format(kb)
        }
    }

    private fun updateProgressList() {
        runOnUiThread {
            progressContainer.removeAllViews()

            var isAnyActive = false
            var totalCount = 0
            val tauriFiles = lastTauriFiles
            if (tauriFiles != null && tauriFiles.length() > 0) {
                totalCount = tauriFiles.length()
                for (i in 0 until tauriFiles.length()) {
                    val f = tauriFiles.optJSONObject(i) ?: continue
                    val fStatus = f.optString("status", "-")
                    if (fStatus != "Transfer Complete" && fStatus != "Already in Destination") {
                        isAnyActive = true
                        break
                    }
                }
            } else {
                val files = md5Files()
                totalCount = files.size
                val curFile = BridgeService.currentFile
                val curProgress = BridgeService.currentProgress
                for (file in files) {
                    var expectedSize = fileExpectedSizeMap[file.name] ?: 0L
                    if (expectedSize == 0L) expectedSize = file.length()
                    val p = if (expectedSize > 0L) ((file.length() * 100) / expectedSize).toInt() else 100
                    val sambaProgress = if (file.name == curFile) curProgress else 0
                    if (p < 100 || sambaProgress < 100) {
                        isAnyActive = true
                        break
                    }
                }
            }

            if (isAnyActive) {
                wasTransferring = true
                if (::confettiView.isInitialized && confettiView.isRunning()) {
                    confettiView.stopConfetti()
                }
            } else if (wasTransferring) {
                wasTransferring = false
                if (::confettiView.isInitialized) {
                    confettiView.startConfetti()
                }
            }

            if (tauriFiles != null && tauriFiles.length() > 0) {
                var renderedCount = 0
                val ringSize = dpToPx(48)
                val ringMargin = dpToPx(8)
                
                for (i in 0 until tauriFiles.length()) {
                    val f = tauriFiles.optJSONObject(i) ?: continue
                    val fName = f.optString("name", "-")
                    val fSize = f.optLong("size", 0)
                    val fStatus = f.optString("status", "-")
                    
                    if (fStatus == "Transfer Complete" || fStatus == "Already in Destination") {
                        continue
                    }
                    
                    var phoneProgress = 0
                    var sambaProgress = 0
                    
                    if (fStatus.startsWith("Pushing to Phone")) {
                        val re = Regex("""\((\d+)%\)""")
                        val match = re.find(fStatus)
                        phoneProgress = match?.groupValues?.get(1)?.toIntOrNull() ?: 0
                    } else if (fStatus == "Staged on Phone") {
                        phoneProgress = 100
                    } else if (fStatus.startsWith("Uploading to Samba")) {
                        phoneProgress = 100
                        val re = Regex("""\((\d+)%\)""")
                        val match = re.find(fStatus)
                        sambaProgress = match?.groupValues?.get(1)?.toIntOrNull() ?: 0
                    } else {
                        continue
                    }
                    
                    renderedCount++
                    
                    val row = LinearLayout(this).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        setPadding(0, dpToPx(12), 0, dpToPx(12))
                        layoutParams = LinearLayout.LayoutParams(-1, -2)
                    }
                    
                    val infoLayout = LinearLayout(this).apply {
                        orientation = LinearLayout.VERTICAL
                        layoutParams = LinearLayout.LayoutParams(0, -2, 1.0f).apply {
                            rightMargin = dpToPx(12)
                        }
                    }
                    val nameTv = TextView(this).apply {
                        text = fName
                        textSize = 12f
                        typeface = android.graphics.Typeface.DEFAULT_BOLD
                        setTextColor(Color.WHITE)
                        ellipsize = android.text.TextUtils.TruncateAt.END
                        maxLines = 1
                    }
                    val sizeTv = TextView(this).apply {
                        text = formatFileSize(fSize)
                        textSize = 10f
                        setTextColor(0xffa1a1aa.toInt())
                    }
                    infoLayout.addView(nameTv)
                    infoLayout.addView(sizeTv)
                    row.addView(infoLayout)
                    
                    val phoneRing = RingProgressView(this).apply {
                        progress = phoneProgress
                        layoutParams = LinearLayout.LayoutParams(ringSize, ringSize).apply {
                            rightMargin = ringMargin
                        }
                    }
                    row.addView(phoneRing)
                    
                    val sambaRing = RingProgressView(this).apply {
                        progress = sambaProgress
                        layoutParams = LinearLayout.LayoutParams(ringSize, ringSize)
                    }
                    row.addView(sambaRing)
                    
                    progressContainer.addView(row)
                    
                    val divider = View(this).apply {
                        setBackgroundColor(0xff27272a.toInt())
                        layoutParams = LinearLayout.LayoutParams(-1, dpToPx(1))
                    }
                    progressContainer.addView(divider)
                }
                
                if (renderedCount == 0) {
                    showEmptyProgressMessage()
                }
            } else {
                val files = md5Files()
                if (files.isEmpty()) {
                    showEmptyProgressMessage()
                    return@runOnUiThread
                }
                
                val curFile = BridgeService.currentFile
                val curProgress = BridgeService.currentProgress
                val ringSize = dpToPx(48)
                val ringMargin = dpToPx(8)
                
                for (file in files) {
                    val row = LinearLayout(this).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        setPadding(0, dpToPx(12), 0, dpToPx(12))
                        layoutParams = LinearLayout.LayoutParams(-1, -2)
                    }
                    
                    val infoLayout = LinearLayout(this).apply {
                        orientation = LinearLayout.VERTICAL
                        layoutParams = LinearLayout.LayoutParams(0, -2, 1.0f).apply {
                            rightMargin = dpToPx(12)
                        }
                    }
                    val nameTv = TextView(this).apply {
                        text = file.name
                        textSize = 12f
                        typeface = android.graphics.Typeface.DEFAULT_BOLD
                        setTextColor(Color.WHITE)
                        ellipsize = android.text.TextUtils.TruncateAt.END
                        maxLines = 1
                    }
                    var expectedSize = fileExpectedSizeMap[file.name] ?: 0L
                    if (expectedSize == 0L) {
                        val metaFile = File(file.parentFile, file.name + ".meta")
                        if (metaFile.exists()) {
                            try {
                                val size = metaFile.readText().trim().toLong()
                                if (size > 0L) {
                                    fileExpectedSizeMap[file.name] = size
                                    expectedSize = size
                                }
                            } catch (e: Exception) {}
                        }
                    }
                    val currentSize = file.length()
                    val speedStr = transferSpeedMap[file.name] ?: ""
                    val sizeText = if (expectedSize > 0L) {
                        "${formatFileSize(currentSize)} / ${formatFileSize(expectedSize)}$speedStr"
                    } else {
                        formatFileSize(currentSize)
                    }
                    val sizeTv = TextView(this).apply {
                        text = sizeText
                        textSize = 10f
                        setTextColor(0xffa1a1aa.toInt())
                    }
                    infoLayout.addView(nameTv)
                    infoLayout.addView(sizeTv)
                    row.addView(infoLayout)
                    
                    val phoneRing = RingProgressView(this).apply {
                        val p = if (expectedSize > 0L) ((file.length() * 100) / expectedSize).toInt() else 100
                        progress = Math.min(100, p)
                        layoutParams = LinearLayout.LayoutParams(ringSize, ringSize).apply {
                            rightMargin = ringMargin
                        }
                    }
                    row.addView(phoneRing)
                    
                    val sambaRing = RingProgressView(this).apply {
                        progress = if (file.name == curFile) curProgress else 0
                        layoutParams = LinearLayout.LayoutParams(ringSize, ringSize)
                    }
                    row.addView(sambaRing)
                    
                    progressContainer.addView(row)
                    
                    val divider = View(this).apply {
                        setBackgroundColor(0xff27272a.toInt())
                        layoutParams = LinearLayout.LayoutParams(-1, dpToPx(1))
                    }
                    progressContainer.addView(divider)
                }
            }
        }
    }

    private fun showEmptyProgressMessage() {
        val emptyTv = TextView(this).apply {
            text = "No active staging transfers"
            textSize = 13f
            setTextColor(0xffa1a1aa.toInt())
            gravity = Gravity.CENTER
            setPadding(0, dpToPx(24), 0, dpToPx(24))
        }
        progressContainer.addView(emptyTv)
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
                    val type = json.optString("type")
                    if (type == "tauri" || type == "snapshot" || type == "state") {
                        val tauriArray = json.optJSONArray("tauri")
                        if (tauriArray != null && tauriArray.length() > 0) {
                            val host = tauriArray.optJSONObject(0)
                            val files = host?.optJSONArray("files")
                            if (files != null) {
                                lastTauriFiles = files
                                runOnUiThread {
                                    updateProgressList()
                                }
                            }
                        }
                    }
                    if (json.has("command")) {
                        val command = json.getString("command")
                        runOnUiThread {
                            when (command) {
                                "upload", "upload_all" -> {
                                    appendLog("Remote command: upload all files")
                                    startAllUpload()
                                }
                                "transfer_progress" -> {
                                    val file = json.optString("file")
                                    val percent = json.optInt("percent", 0)
                                    val totalSize = json.optLong("total_size", 0L)
                                    val speedMbps = json.optDouble("speed_mbps", 0.0)
                                    if (file.isNotEmpty()) {
                                        transferProgressMap[file] = percent
                                        if (totalSize > 0L) {
                                            fileExpectedSizeMap[file] = totalSize
                                        }
                                        if (speedMbps > 0.0) {
                                            transferSpeedMap[file] = " . %.2f MB/s".format(speedMbps)
                                            adbPushSpeedMap[file] = (speedMbps * 1024 * 1024).toLong()
                                        } else {
                                            adbPushSpeedMap[file] = 0L
                                        }
                                        updateProgressList()
                                    }
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
        private val adbPushPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(249, 115, 22)
            strokeWidth = 4f
            style = Paint.Style.STROKE
        }
        private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
        }
        private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xffd4d4d8.toInt()
            textSize = 24f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
        private val bgPaint = Paint().apply {
            color = 0xff18181b.toInt()
        }

        override fun onDraw(canvas: Canvas) {
            val rect = RectF(0f, 0f, width.toFloat(), height.toFloat())
            canvas.drawRoundRect(rect, 16f, 16f, bgPaint)
            
            val maxBytes = max(1L, networkHistory.flatMap { listOf(it.first, it.second, it.third) }.maxOrNull() ?: 1L)
            
            drawCurve(canvas, 1, maxBytes, upPaint) // Samba
            drawCurve(canvas, 2, maxBytes, adbPushPaint) // Adb Push
            
            val last = networkHistory.lastOrNull() ?: Triple(0L, 0L, 0L)
            canvas.drawText("Samba: ${mbps(last.second)} MB/s", 24f, 40f, textPaint)
            canvas.drawText("ADB Push: ${mbps(last.third)} MB/s", 24f, 80f, textPaint)
        }

        private fun drawCurve(canvas: Canvas, index: Int, maxBytes: Long, paint: Paint) {
            if (networkHistory.size < 2) return
            val path = android.graphics.Path()
            networkHistory.forEachIndexed { idx, point ->
                val x = idx * width.toFloat() / (networkHistory.size - 1)
                val bytes = when (index) {
                    0 -> point.first
                    1 -> point.second
                    else -> point.third
                }
                val y = height - (bytes.toFloat() / maxBytes) * height
                if (idx == 0) {
                    path.moveTo(x, y)
                } else {
                    val prevX = (idx - 1) * width.toFloat() / (networkHistory.size - 1)
                    val prevBytes = when (index) {
                        0 -> networkHistory[idx - 1].first
                        1 -> networkHistory[idx - 1].second
                        else -> networkHistory[idx - 1].third
                    }
                    val prevY = height - (prevBytes.toFloat() / maxBytes) * height
                    
                    val cp1x = prevX + (x - prevX) / 2
                    val cp1y = prevY
                    val cp2x = prevX + (x - prevX) / 2
                    val cp2y = y
                    path.cubicTo(cp1x, cp1y, cp2x, cp2y, x, y)
                }
            }
            
            val fillPath = android.graphics.Path(path).apply {
                lineTo(width.toFloat(), height.toFloat())
                lineTo(0f, height.toFloat())
                close()
            }
            val color = paint.color
            val shader = android.graphics.LinearGradient(
                0f, 0f, 0f, height.toFloat(),
                Color.argb(32, Color.red(color), Color.green(color), Color.blue(color)),
                Color.argb(0, Color.red(color), Color.green(color), Color.blue(color)),
                android.graphics.Shader.TileMode.CLAMP
            )
            fillPaint.shader = shader
            canvas.drawPath(fillPath, fillPaint)
            
            canvas.drawPath(path, paint)
        }

        private fun mbps(bytes: Long) = "%.2f".format(bytes / 1024.0 / 1024.0)
    }

    class RingProgressView(context: Context) : View(context) {
        var progress: Int = 0
            set(value) {
                field = value
                postInvalidate()
            }
        
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 6f
        }
        
        private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 18f
            textAlign = Paint.Align.CENTER
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
        
        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat()
            val h = height.toFloat()
            val r = Math.min(w, h) / 2f - 8f
            val cx = w / 2f
            val cy = h / 2f
            
            // Draw track
            paint.style = Paint.Style.STROKE
            paint.color = Color.parseColor("#27272a")
            canvas.drawCircle(cx, cy, r, paint)
            
            // Draw progress arc
            paint.color = if (progress >= 100) Color.parseColor("#16a34a") else Color.parseColor("#2563eb")
            val rect = RectF(cx - r, cy - r, cx + r, cy + r)
            canvas.drawArc(rect, -90f, (progress * 3.6f), false, paint)
            
            // Draw text
            val density = resources.displayMetrics.density
            textPaint.textSize = 10f * density
            textPaint.color = Color.WHITE
            canvas.drawText("$progress%", cx, cy - (textPaint.descent() + textPaint.ascent()) / 2f, textPaint)
        }
    }

    private fun adjustOrientationLayout() {
        if (!::rootLayout.isInitialized || !::leftColumn.isInitialized || !::rightColumn.isInitialized || !::leftExpandBar.isInitialized || !::collapseBtn.isInitialized || !::leftDetailsContainer.isInitialized) return
        
        rootLayout.removeAllViews()
        val isLandscape = resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
        
        if (isLandscape) {
            rootLayout.orientation = LinearLayout.HORIZONTAL
            rootLayout.setPadding(dpToPx(16), dpToPx(16), dpToPx(16), dpToPx(16))
            
            collapseBtn.visibility = View.VISIBLE
            leftDetailsContainer.visibility = View.VISIBLE
            
            networkChart.layoutParams = LinearLayout.LayoutParams(-1, 0, 1.0f).apply {
                bottomMargin = dpToPx(8)
            }
            
            val barParams = LinearLayout.LayoutParams(dpToPx(40), -1).apply {
                rightMargin = dpToPx(16)
            }
            val leftParams = LinearLayout.LayoutParams(0, -2, 1.0f).apply {
                rightMargin = dpToPx(16)
                topMargin = 0
            }
            val rightParams = LinearLayout.LayoutParams(0, -1, 1.2f)
            
            rootLayout.addView(leftExpandBar, barParams)
            rootLayout.addView(leftColumn, leftParams)
            rootLayout.addView(rightColumn, rightParams)
            
            if (isMinimized) {
                leftColumn.visibility = View.GONE
                leftExpandBar.visibility = View.VISIBLE
            } else {
                leftColumn.visibility = View.VISIBLE
                leftExpandBar.visibility = View.GONE
            }
        } else {
            rootLayout.orientation = LinearLayout.VERTICAL
            rootLayout.setPadding(dpToPx(16), dpToPx(16), dpToPx(16), dpToPx(16))
            
            leftExpandBar.visibility = View.GONE
            leftColumn.visibility = View.VISIBLE
            collapseBtn.visibility = View.GONE
            
            if (isMinimized) {
                leftDetailsContainer.visibility = View.GONE
            } else {
                leftDetailsContainer.visibility = View.VISIBLE
            }
            
            networkChart.layoutParams = LinearLayout.LayoutParams(-1, dpToPx(120)).apply {
                bottomMargin = dpToPx(12)
            }
            
            val rightParams = LinearLayout.LayoutParams(-1, 0, 1.0f)
            val leftParams = LinearLayout.LayoutParams(-1, -2).apply {
                topMargin = dpToPx(16)
                rightMargin = 0
            }
            
            networkChart.visibility = if (isChartExpanded) View.VISIBLE else View.GONE
            if (::chartAccordionBtn.isInitialized) {
                chartAccordionBtn.text = if (isChartExpanded) "▲ Collapse" else "▼ Expand"
            }
            
            rootLayout.addView(rightColumn, rightParams)
            rootLayout.addView(leftColumn, leftParams)
        }
        
        networkChart.visibility = if (isChartExpanded) View.VISIBLE else View.GONE
        if (::chartAccordionBtn.isInitialized) {
            chartAccordionBtn.text = if (isChartExpanded) "▲ Collapse" else "▼ Expand"
        }
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        adjustOrientationLayout()
    }

    private fun setupSwipeGestures() {
        var startX = 0f
        var startY = 0f
        
        val leftColumnTouchListener = View.OnTouchListener { v, event ->
            val isLandscape = resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
            
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = event.x
                    startY = event.y
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val deltaX = event.x - startX
                    val deltaY = event.y - startY
                    
                    if (isLandscape) {
                        // Landscape: swipe left to collapse
                        if (deltaX < -150f && Math.abs(deltaY) < 150f) {
                            leftColumn.visibility = View.GONE
                            leftExpandBar.visibility = View.VISIBLE
                            isMinimized = true
                            true
                        } else {
                            v.performClick()
                            false
                        }
                    } else {
                        // Portrait: swipe down on details container to collapse
                        if (deltaY > 150f && Math.abs(deltaX) < 150f) {
                            leftDetailsContainer.visibility = View.GONE
                            isMinimized = true
                            true
                        } else {
                            v.performClick()
                            false
                        }
                    }
                }
                else -> false
            }
        }
        leftColumn.setOnTouchListener(leftColumnTouchListener)
        leftDetailsContainer.setOnTouchListener(leftColumnTouchListener)
        
        val expandBarTouchListener = View.OnTouchListener { v, event ->
            val isLandscape = resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
            
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = event.x
                    startY = event.y
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val deltaX = event.x - startX
                    val deltaY = event.y - startY
                    
                    if (isLandscape) {
                        // Landscape: swipe right to expand
                        if (deltaX > 100f && Math.abs(deltaY) < 150f) {
                            leftColumn.visibility = View.VISIBLE
                            leftExpandBar.visibility = View.GONE
                            isMinimized = false
                            true
                        } else {
                            v.performClick()
                            false
                        }
                    } else {
                        v.performClick()
                        false
                    }
                }
                else -> false
            }
        }
        leftExpandBar.setOnTouchListener(expandBarTouchListener)

        val titleRowTouchListener = View.OnTouchListener { v, event ->
            val isLandscape = resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
            if (isLandscape) return@OnTouchListener false
            
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = event.x
                    startY = event.y
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val deltaX = event.x - startX
                    val deltaY = event.y - startY
                    // Portrait: swipe up on title to expand
                    if (deltaY < -100f && Math.abs(deltaX) < 150f) {
                        leftDetailsContainer.visibility = View.VISIBLE
                        isMinimized = false
                        true
                    } else {
                        v.performClick()
                        false
                    }
                }
                else -> false
            }
        }
        titleRow.setOnTouchListener(titleRowTouchListener)
    }

    inner class BatteryProgressView(context: Context) : View(context) {
        private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#27272a")
            strokeWidth = 6f
            strokeCap = Paint.Cap.ROUND
            style = Paint.Style.STROKE
        }
        private val glowPaint3 = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(40, 34, 197, 94)
            strokeWidth = 16f
            strokeCap = Paint.Cap.ROUND
            style = Paint.Style.STROKE
        }
        private val glowPaint2 = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(80, 34, 197, 94)
            strokeWidth = 10f
            strokeCap = Paint.Cap.ROUND
            style = Paint.Style.STROKE
        }
        private val corePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#22c55e")
            strokeWidth = 4f
            strokeCap = Paint.Cap.ROUND
            style = Paint.Style.STROKE
        }

        init {
            post(object : Runnable {
                override fun run() {
                    invalidate()
                    postDelayed(this, 1000)
                }
            })
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat()
            val h = height.toFloat()
            
            val cx = w / 2f
            val top = dpToPx(32).toFloat()
            val bottom = h - dpToPx(32).toFloat()
            
            canvas.drawLine(cx, top, cx, bottom, trackPaint)
            
            var percent = 0f
            val tauriFiles = lastTauriFiles
            if (tauriFiles != null && tauriFiles.length() > 0) {
                var totalRings = 0
                var completedRings = 0f
                for (i in 0 until tauriFiles.length()) {
                    val f = tauriFiles.optJSONObject(i) ?: continue
                    val fStatus = f.optString("status", "-")
                    totalRings += 2
                    
                    if (fStatus == "Transfer Complete" || fStatus == "Already in Destination") {
                        completedRings += 2f
                    } else {
                        var phoneProgress = 0
                        var sambaProgress = 0
                        if (fStatus.startsWith("Pushing to Phone")) {
                            val re = Regex("""\((\d+)%\)""")
                            val match = re.find(fStatus)
                            phoneProgress = match?.groupValues?.get(1)?.toIntOrNull() ?: 0
                        } else if (fStatus == "Staged on Phone") {
                            phoneProgress = 100
                        } else if (fStatus.startsWith("Uploading to Samba")) {
                            phoneProgress = 100
                            val re = Regex("""\((\d+)%\)""")
                            val match = re.find(fStatus)
                            sambaProgress = match?.groupValues?.get(1)?.toIntOrNull() ?: 0
                        }
                        completedRings += (phoneProgress / 100f) + (sambaProgress / 100f)
                    }
                }
                if (totalRings > 0) {
                    percent = completedRings / totalRings
                }
            } else {
                val files = md5Files()
                val totalRings = files.size * 2
                var completedRings = 0f
                val curFile = BridgeService.currentFile
                val curProgress = BridgeService.currentProgress
                for (file in files) {
                    var expectedSize = fileExpectedSizeMap[file.name] ?: 0L
                    if (expectedSize == 0L) expectedSize = file.length()
                    val p = if (expectedSize > 0L) ((file.length() * 100) / expectedSize).toInt() else 100
                    val phoneProgress = Math.min(100, p)
                    val sambaProgress = if (file.name == curFile) curProgress else 0
                    completedRings += (phoneProgress / 100f) + (sambaProgress / 100f)
                }
                if (totalRings > 0) {
                    percent = completedRings / totalRings
                }
            }
            
            if (percent > 0f) {
                val progressTop = bottom - (bottom - top) * percent
                canvas.drawLine(cx, bottom, cx, progressTop, glowPaint3)
                canvas.drawLine(cx, bottom, cx, progressTop, glowPaint2)
                canvas.drawLine(cx, bottom, cx, progressTop, corePaint)
            }
        }
    }

    inner class ConfettiView(context: Context) : View(context) {
        private var isRunning = false
        private val random = java.util.Random()
        private val particles = mutableListOf<Particle>()
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        private inner class Particle(
            var x: Float,
            var y: Float,
            var vx: Float,
            var vy: Float,
            var size: Float,
            var color: Int,
            var rotation: Float,
            var rotationSpeed: Float,
            var isCircle: Boolean
        )

        fun isRunning(): Boolean = isRunning

        fun startConfetti() {
            if (isRunning) return
            isRunning = true
            visibility = View.VISIBLE
            post {
                particles.clear()
                val colors = intArrayOf(
                    Color.parseColor("#ef4444"),
                    Color.parseColor("#3b82f6"),
                    Color.parseColor("#22c55e"),
                    Color.parseColor("#eab308"),
                    Color.parseColor("#a855f7"),
                    Color.parseColor("#ec4899"),
                    Color.parseColor("#06b6d4"),
                    Color.parseColor("#f97316")
                )

                val w = if (width > 0) width.toFloat() else resources.displayMetrics.widthPixels.toFloat()
                val h = if (height > 0) height.toFloat() else resources.displayMetrics.heightPixels.toFloat()

                for (i in 0 until 120) {
                    particles.add(
                        Particle(
                            x = random.nextFloat() * w,
                            y = -random.nextFloat() * h * 0.8f,
                            vx = (random.nextFloat() - 0.5f) * 6f,
                            vy = 4f + random.nextFloat() * 10f,
                            size = (8 + random.nextInt(12)) * resources.displayMetrics.density,
                            color = colors[random.nextInt(colors.size)],
                            rotation = random.nextFloat() * 360f,
                            rotationSpeed = (random.nextFloat() - 0.5f) * 10f,
                            isCircle = random.nextBoolean()
                        )
                    )
                }
                invalidate()
            }
        }

        fun stopConfetti() {
            if (!isRunning) return
            isRunning = false
            particles.clear()
            visibility = View.GONE
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            if (!isRunning || particles.isEmpty()) return

            val w = if (width > 0) width.toFloat() else resources.displayMetrics.widthPixels.toFloat()
            val h = if (height > 0) height.toFloat() else resources.displayMetrics.heightPixels.toFloat()

            for (p in particles) {
                p.x += p.vx
                p.y += p.vy
                p.rotation += p.rotationSpeed

                if (p.y > h) {
                    p.y = -20f
                    p.x = random.nextFloat() * w
                }

                canvas.save()
                canvas.translate(p.x, p.y)
                canvas.rotate(p.rotation)
                paint.color = p.color

                if (p.isCircle) {
                    canvas.drawCircle(0f, 0f, p.size / 2f, paint)
                } else {
                    canvas.drawRect(-p.size / 2f, -p.size / 4f, p.size / 2f, p.size / 4f, paint)
                }
                canvas.restore()
            }

            postInvalidateOnAnimation()
        }
    }
}


