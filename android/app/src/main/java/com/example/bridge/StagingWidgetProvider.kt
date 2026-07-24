package com.example.bridge

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.widget.RemoteViews
import org.json.JSONArray

class StagingWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        for (appWidgetId in appWidgetIds) {
            updateWidget(context, appWidgetManager, appWidgetId, null)
        }
    }

    companion object {
        fun updateAll(context: Context, tauriJson: JSONArray?) {
            val appWidgetManager = AppWidgetManager.getInstance(context)
            val ids = appWidgetManager.getAppWidgetIds(ComponentName(context, StagingWidgetProvider::class.java))
            for (id in ids) {
                updateWidget(context, appWidgetManager, id, tauriJson)
            }
        }

        private fun updateWidget(context: Context, appWidgetManager: AppWidgetManager, appWidgetId: Int, tauriJson: JSONArray?) {
            val views = RemoteViews(context.packageName, R.layout.widget_staging_layout)

            val sb = StringBuilder()
            if (tauriJson != null && tauriJson.length() > 0) {
                for (i in 0 until tauriJson.length()) {
                    val host = tauriJson.optJSONObject(i) ?: continue
                    val hostName = host.optString("host", "Host")
                    val files = host.optJSONArray("files")
                    sb.append("🖥 ").append(hostName).append("\n")
                    if (files != null && files.length() > 0) {
                        for (j in 0 until Math.min(files.length(), 5)) {
                            val f = files.optJSONObject(j) ?: continue
                            val fName = f.optString("name", "-")
                            val fStatus = f.optString("status", "-")
                            sb.append("  • ").append(fName).append(" [").append(fStatus).append("]\n")
                        }
                    } else {
                        sb.append("  (No Files In Staging)\n")
                    }
                }
            } else {
                sb.append("No active staging hosts.")
            }

            views.setTextViewText(R.id.widgetStagingContent, sb.toString().trim())
            appWidgetManager.updateAppWidget(appWidgetId, views)
        }
    }
}
