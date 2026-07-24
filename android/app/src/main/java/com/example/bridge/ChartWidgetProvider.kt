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
        fun updateAll(context: Context, devicesJson: JSONArray?) {
            val appWidgetManager = AppWidgetManager.getInstance(context)
            val ids = appWidgetManager.getAppWidgetIds(ComponentName(context, ChartWidgetProvider::class.java))
            for (id in ids) {
                updateWidget(context, appWidgetManager, id, devicesJson)
            }
        }

        private fun updateWidget(context: Context, appWidgetManager: AppWidgetManager, appWidgetId: Int, devicesJson: JSONArray?) {
            val views = RemoteViews(context.packageName, R.layout.widget_chart_layout)

            val width = 400
            val height = 200
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)

            canvas.drawColor(Color.parseColor("#18181b"))

            val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#27272a")
                strokeWidth = 2f
            }
            for (i in 1..4) {
                val y = (height / 5f) * i
                canvas.drawLine(0f, y, width.toFloat(), y, gridPaint)
            }

            if (devicesJson != null && devicesJson.length() > 0) {
                val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.parseColor("#60a5fa")
                    strokeWidth = 4f
                    style = Paint.Style.STROKE
                }
                var maxBps = 1.0
                for (i in 0 until devicesJson.length()) {
                    val dev = devicesJson.optJSONObject(i) ?: continue
                    val samples = dev.optJSONArray("samples") ?: continue
                    for (j in 0 until samples.length()) {
                        val tx = samples.optJSONObject(j)?.optDouble("tx_bps", 0.0) ?: 0.0
                        if (tx > maxBps) maxBps = tx
                    }
                }
                val dev = devicesJson.optJSONObject(0)
                val samples = dev?.optJSONArray("samples")
                if (samples != null && samples.length() >= 2) {
                    val path = Path()
                    val count = Math.min(samples.length(), 40)
                    val start = samples.length() - count
                    for (j in 0 until count) {
                        val tx = samples.optJSONObject(start + j)?.optDouble("tx_bps", 0.0) ?: 0.0
                        val x = j * width.toFloat() / (count - 1)
                        val y = height - ((tx / maxBps) * (height * 0.85f)).toFloat()
                        if (j == 0) path.moveTo(x, y) else path.lineTo(x, y)
                    }
                    canvas.drawPath(path, strokePaint)
                }
            }

            views.setImageViewBitmap(R.id.widgetChartImage, bitmap)
            appWidgetManager.updateAppWidget(appWidgetId, views)
        }
    }
}
