package com.example.bridge

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.graphics.*
import android.widget.RemoteViews
import org.json.JSONArray

class ChartWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        for (appWidgetId in appWidgetIds) {
            updateWidget(context, appWidgetManager, appWidgetId, null)
        }
    }

    companion object {
        private val colors = intArrayOf(
            Color.parseColor("#60a5fa"),
            Color.parseColor("#f472b6"),
            Color.parseColor("#a78bfa"),
            Color.parseColor("#34d399"),
            Color.parseColor("#fb923c")
        )

        fun updateAll(context: Context, devicesJson: JSONArray?) {
            if (devicesJson != null) {
                val prefs = context.getSharedPreferences("monitor_cache", Context.MODE_PRIVATE)
                prefs.edit().putString("last_devices", devicesJson.toString()).apply()
            }

            val appWidgetManager = AppWidgetManager.getInstance(context)
            val ids = appWidgetManager.getAppWidgetIds(ComponentName(context, ChartWidgetProvider::class.java))
            for (id in ids) {
                updateWidget(context, appWidgetManager, id, devicesJson)
            }
        }

        private fun updateWidget(context: Context, appWidgetManager: AppWidgetManager, appWidgetId: Int, inputJson: JSONArray?) {
            val views = RemoteViews(context.packageName, R.layout.widget_chart_layout)

            val devicesJson = inputJson ?: run {
                val prefs = context.getSharedPreferences("monitor_cache", Context.MODE_PRIVATE)
                val str = prefs.getString("last_devices", null)
                if (str != null) try { JSONArray(str) } catch (e: Exception) { null } else null
            }

            val width = 450
            val height = 220
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)

            canvas.drawColor(Color.parseColor("#18181b"))

            val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#27272a")
                strokeWidth = 2f
                style = Paint.Style.STROKE
            }
            for (i in 1..4) {
                val y = (height / 5f) * i
                canvas.drawLine(0f, y, width.toFloat(), y, gridPaint)
            }

            if (devicesJson != null && devicesJson.length() > 0) {
                var maxBps = 1.0
                for (i in 0 until devicesJson.length()) {
                    val dev = devicesJson.optJSONObject(i) ?: continue
                    val samples = dev.optJSONArray("samples") ?: continue
                    for (j in 0 until samples.length()) {
                        val tx = samples.optJSONObject(j)?.optDouble("tx_bps", 0.0) ?: 0.0
                        if (tx > maxBps) maxBps = tx
                    }
                }

                val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    textSize = 22f
                    typeface = Typeface.DEFAULT_BOLD
                }

                for (i in 0 until devicesJson.length()) {
                    val dev = devicesJson.optJSONObject(i) ?: continue
                    val samples = dev.optJSONArray("samples") ?: continue
                    if (samples.length() < 2) continue

                    val strokeColor = colors[i % colors.size]
                    val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = strokeColor
                        strokeWidth = 4f
                        style = Paint.Style.STROKE
                        strokeCap = Paint.Cap.ROUND
                    }

                    val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = strokeColor
                        style = Paint.Style.FILL
                        alpha = 30
                    }

                    val linePath = Path()
                    val fillPath = Path()

                    val sampleCount = Math.min(samples.length(), 50)
                    val startIndex = samples.length() - sampleCount

                    var firstX = 0f
                    var lastX = 0f

                    for (j in 0 until sampleCount) {
                        val s = samples.optJSONObject(startIndex + j) ?: continue
                        val tx = s.optDouble("tx_bps", 0.0)
                        val x = j * width.toFloat() / (sampleCount - 1)
                        val y = height - ((tx / maxBps) * (height * 0.82f)).toFloat()

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

                    fillPath.lineTo(lastX, height.toFloat())
                    fillPath.lineTo(firstX, height.toFloat())
                    fillPath.close()

                    canvas.drawPath(fillPath, fillPaint)
                    canvas.drawPath(linePath, linePaint)

                    val label = dev.optString("model", "Device ${i + 1}")
                    textPaint.color = strokeColor
                    canvas.drawText(label, 16f, 30f + (i * 26f), textPaint)
                }
            }

            views.setImageViewBitmap(R.id.widgetChartImage, bitmap)
            appWidgetManager.updateAppWidget(appWidgetId, views)
        }
    }
}
