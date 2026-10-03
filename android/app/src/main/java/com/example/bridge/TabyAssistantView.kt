package com.example.bridge

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebSettings
import android.webkit.WebView
import android.widget.FrameLayout

@SuppressLint("SetJavaScriptEnabled")
class TabyAssistantView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    private val webView = WebView(context)
    private val handler = Handler(Looper.getMainLooper())
    var onExitListener: (() -> Unit)? = null

    init {
        setBackgroundColor(Color.BLACK)
        webView.apply {
            setBackgroundColor(Color.BLACK)
            layoutParams = LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                allowFileAccess = true
                allowContentAccess = true
                setRenderPriority(WebSettings.RenderPriority.HIGH)
                cacheMode = WebSettings.LOAD_DEFAULT
            }
            addJavascriptInterface(WebAppInterface(), "AndroidBridge")
            loadUrl("file:///android_asset/taby_view.html")
        }
        addView(webView)
    }

    inner class WebAppInterface {
        @JavascriptInterface
        fun closeFaceMode() {
            handler.post {
                onExitListener?.invoke()
            }
        }
    }

    fun playExpression(animKey: String, title: String, subtext: String = "", durationMs: Long = 0) {
        handler.post {
            val safeKey = animKey.replace("'", "\\'")
            val safeTitle = title.replace("'", "\\'")
            val safeSub = subtext.replace("'", "\\'")
            webView.evaluateJavascript(
                "playExpression('$safeKey', '$safeTitle', '$safeSub', $durationMs);",
                null
            )
        }
    }

    fun setTransferProgress(speedText: String, percent: Int, fileName: String) {
        handler.post {
            val safeSpeed = speedText.replace("'", "\\'")
            val safeFile = fileName.replace("'", "\\'")
            webView.evaluateJavascript(
                "setTransferProgress('$safeSpeed', $percent, '$safeFile');",
                null
            )
        }
    }

    fun setAdbPushProgress(speedText: String, percent: Int, fileName: String) {
        handler.post {
            val safeSpeed = speedText.replace("'", "\\'")
            val safeFile = fileName.replace("'", "\\'")
            webView.evaluateJavascript(
                "setAdbPushProgress('$safeSpeed', $percent, '$safeFile');",
                null
            )
        }
    }

    fun setRotation(deg: Int) {
        handler.post {
            webView.evaluateJavascript("setRotation($deg);", null)
        }
    }

    fun updateConfig(configJson: String) {
        handler.post {
            val safeJson = configJson.replace("'", "\\'")
            webView.evaluateJavascript("updateConfig('$safeJson');", null)
        }
    }
}
