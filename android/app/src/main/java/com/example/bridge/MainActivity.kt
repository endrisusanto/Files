package com.example.bridge

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.File

class MainActivity : Activity() {
    private val localDir by lazy { File(getExternalFilesDir(null), "SUBRO") }
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }

        status = TextView(this).apply {
            textSize = 16f
            setLineSpacing(6f, 1.05f)
            setTextColor(0xffd4d4d8.toInt())
        }
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
            addView(status, LinearLayout.LayoutParams(-1, -2).apply {
                bottomMargin = 36
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
        refreshStatus()
    }

    private fun latestFile(): File? =
        localDir.listFiles()
            ?.filter { it.isFile && it.name.endsWith(".tar.md5") }
            ?.maxByOrNull { it.lastModified() }

    private fun startLatestUpload() {
        val file = latestFile()
        if (file == null) {
            refreshStatus("No .tar.md5 file found")
            return
        }
        val intent = Intent(this, BridgeService::class.java).putExtra("file", file.name)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent) else startService(intent)
        refreshStatus("Uploading ${file.name}")
    }

    private fun refreshStatus(message: String? = null) {
        localDir.mkdirs()
        val file = latestFile()
        status.text = listOfNotNull(
            message,
            "Staging: ${localDir.absolutePath}",
            "Latest: ${file?.name ?: "-"}",
            "Target: smb://192.168.10.221/sambashare/"
        ).joinToString("\n")
    }
}
