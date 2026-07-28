package com.example.bridge

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.widget.RemoteViews
import org.json.JSONArray
import java.util.Locale

class StagingWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        for (appWidgetId in appWidgetIds) {
            updateWidget(context, appWidgetManager, appWidgetId, null)
        }
    }

    companion object {
        fun updateAll(context: Context, tauriJson: JSONArray?) {
            if (tauriJson != null) {
                val prefs = context.getSharedPreferences("monitor_cache", Context.MODE_PRIVATE)
                prefs.edit().putString("last_tauri", tauriJson.toString()).apply()
            }

            val appWidgetManager = AppWidgetManager.getInstance(context)
            val ids = appWidgetManager.getAppWidgetIds(ComponentName(context, StagingWidgetProvider::class.java))
            for (id in ids) {
                updateWidget(context, appWidgetManager, id, tauriJson)
            }
        }

        private fun updateWidget(context: Context, appWidgetManager: AppWidgetManager, appWidgetId: Int, inputJson: JSONArray?) {
            val views = RemoteViews(context.packageName, R.layout.widget_staging_layout)

            val tauriJson = inputJson ?: run {
                val prefs = context.getSharedPreferences("monitor_cache", Context.MODE_PRIVATE)
                val str = prefs.getString("last_tauri", null)
                if (str != null) try { JSONArray(str) } catch (e: Exception) { null } else null
            }

            views.removeAllViews(R.id.widgetStagingContent)
            if (tauriJson != null && tauriJson.length() > 0) {
                for (i in 0 until tauriJson.length()) {
                    val host = tauriJson.optJSONObject(i) ?: continue
                    val hostName = host.optString("host", "Host")
                    val files = host.optJSONArray("files")
                    val hostViews = RemoteViews(context.packageName, R.layout.widget_staging_host)
                    val online = System.currentTimeMillis() - host.optLong("last_seen", 0) < 15_000
                    hostViews.setTextViewText(R.id.widgetStagingHostName, "🖥 $hostName")
                    hostViews.setTextViewText(R.id.widgetStagingHostStatus, if (online) "Online" else "Offline")
                    hostViews.setTextColor(R.id.widgetStagingHostStatus, if (online) 0xff4ade80.toInt() else 0xfff87171.toInt())
                    if (files != null && files.length() > 0) {
                        for (j in 0 until Math.min(files.length(), 5)) {
                            val f = files.optJSONObject(j) ?: continue
                            val fName = f.optString("name", "-")
                            val fStatus = f.optString("status", "-")
                            val fSize = f.optLong("size", 0).toDouble() / (1024 * 1024 * 1024)
                            val fileViews = RemoteViews(context.packageName, R.layout.widget_staging_file)
                            fileViews.setTextViewText(R.id.widgetStagingFileName, "• $fName\n${String.format(Locale.US, "%.2f GB", fSize)}")
                            fileViews.setTextViewText(R.id.widgetStagingFileStatus, fStatus)
                            fileViews.setTextColor(R.id.widgetStagingFileStatus, statusColor(fStatus))
                            hostViews.addView(R.id.widgetStagingFiles, fileViews)
                        }
                    } else {
                        val empty = RemoteViews(context.packageName, R.layout.widget_staging_empty)
                        empty.setTextViewText(R.id.widgetStagingEmpty, "No files in staging.")
                        hostViews.addView(R.id.widgetStagingFiles, empty)
                    }
                    views.addView(R.id.widgetStagingContent, hostViews)
                }
            } else {
                views.addView(R.id.widgetStagingContent, RemoteViews(context.packageName, R.layout.widget_staging_empty))
            }

            appWidgetManager.updateAppWidget(appWidgetId, views)
        }

        private fun statusColor(status: String): Int = when {
            status.contains("Complete", true) -> 0xff4ade80.toInt()
            status.contains("Uploading", true) -> 0xff22d3ee.toInt()
            status.contains("Pushing", true) -> 0xff60a5fa.toInt()
            status == "Ready" -> 0xffa1a1aa.toInt()
            else -> 0xfffacc15.toInt()
        }
    }
}
