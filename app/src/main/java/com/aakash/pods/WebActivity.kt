package com.aakash.pods

import android.app.Activity
import android.app.ActivityManager
import android.app.KeyguardManager
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.webkit.Profile
import androidx.webkit.ProfileStore
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature

/**
 * Shows one tile. The WebView is bound to the tile's own profile, so its cookies,
 * logins and site data are sealed off from every other tile.
 */
class WebActivity : Activity() {

    private lateinit var tile: Tile
    private lateinit var content: LinearLayout
    private lateinit var progress: ProgressBar
    private var web: WebView? = null
    private var profile: Profile? = null

    private var unlocked = false
    private var pausedAt = 0L
    private var fileCallback: ValueCallback<Array<Uri>>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val id = intent.getStringExtra("id")
        val found = TileStore.load(this).firstOrNull { it.id == id }
        if (found == null) {
            finish()
            return
        }
        tile = found

        setTaskDescription(ActivityManager.TaskDescription(tile.name))
        if (tile.locked) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)

        buildChrome()

        if (!WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) {
            val msg = TextView(this).apply {
                text = "Separate logins need a newer Android System WebView.\nUpdate it in the Play Store, then reopen this site."
                gravity = Gravity.CENTER
                setPadding(dp(32), dp(32), dp(32), dp(32))
            }
            content.addView(msg, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            return
        }

        val w = createWebView()
        web = w
        content.addView(w, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        if (tile.locked) {
            content.visibility = View.INVISIBLE
            requestUnlock()
        } else {
            unlocked = true
            w.loadUrl(tile.url)
        }
    }

    private fun buildChrome() {
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
        }

        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.parseColor("#1B1F3B"))
        }

        fun button(label: String, onClick: () -> Unit): TextView = TextView(this).apply {
            text = label
            textSize = 20f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setOnClickListener { onClick() }
        }

        bar.addView(button("✕") { finish() }, LinearLayout.LayoutParams(dp(48), dp(44)))
        val title = TextView(this).apply {
            text = tile.name
            textSize = 15f
            gravity = Gravity.CENTER_VERTICAL
            setTextColor(Color.WHITE)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        bar.addView(title, LinearLayout.LayoutParams(0, dp(44), 1f))
        bar.addView(button("⟳") { web?.reload() }, LinearLayout.LayoutParams(dp(48), dp(44)))
        content.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44)))

        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            visibility = View.GONE
        }
        content.addView(progress, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(3)))

        setContentView(content)
    }

    private fun createWebView(): WebView {
        val p = ProfileStore.getInstance().getOrCreateProfile(tile.profile)
        profile = p

        val w = WebView(this)
        // Must be the first call on the WebView: it picks which sealed profile this view uses.
        WebViewCompat.setProfile(w, tile.profile)
        w.setBackgroundColor(Color.WHITE)

        val s = w.settings
        val defaultUa = s.userAgentString
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.javaScriptCanOpenWindowsAutomatically = true
        s.setSupportMultipleWindows(false)
        s.allowFileAccess = false
        s.allowContentAccess = false
        s.mediaPlaybackRequiresUserGesture = true
        s.setSupportZoom(true)
        s.builtInZoomControls = true
        s.displayZoomControls = false
        s.userAgentString = buildUserAgent(defaultUa, tile.desktop)
        if (tile.desktop) {
            s.useWideViewPort = true
            s.loadWithOverviewMode = true
        }

        p.cookieManager.setAcceptCookie(true)
        p.cookieManager.setAcceptThirdPartyCookies(w, true)

        w.setDownloadListener { _, _, _, _, _ ->
            Toast.makeText(this, "Downloads aren't supported yet", Toast.LENGTH_SHORT).show()
        }

        w.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val u = request.url
                return when (u.scheme) {
                    "https", "http", "about", "blob", "data" -> false
                    "tel", "mailto", "sms", "geo" -> {
                        try {
                            startActivity(Intent(Intent.ACTION_VIEW, u))
                        } catch (e: Exception) {
                            // No app to handle it.
                        }
                        true
                    }
                    else -> true
                }
            }
        }

        w.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                progress.progress = newProgress
                progress.visibility = if (newProgress in 1..99) View.VISIBLE else View.GONE
            }

            override fun onShowFileChooser(
                webView: WebView,
                filePathCallback: ValueCallback<Array<Uri>>,
                fileChooserParams: FileChooserParams,
            ): Boolean {
                fileCallback?.onReceiveValue(null)
                fileCallback = filePathCallback
                return try {
                    startActivityForResult(fileChooserParams.createIntent(), REQ_FILE)
                    true
                } catch (e: Exception) {
                    fileCallback = null
                    false
                }
            }

            // Camera and microphone are off for now; deny rather than leave the page hanging.
            override fun onPermissionRequest(request: PermissionRequest) {
                request.deny()
            }
        }
        return w
    }

    /**
     * Google refuses sign-in inside an obvious "WebView" browser, so present as plain Chrome.
     * Desktop mode presents as desktop Chrome (WhatsApp Web needs this).
     */
    private fun buildUserAgent(default: String, desktop: Boolean): String {
        val chrome = Regex("Chrome/([0-9.]+)").find(default)?.groupValues?.get(1) ?: "126.0.0.0"
        return if (desktop) {
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/$chrome Safari/537.36"
        } else {
            default.replace("; wv", "").replace(" Version/4.0", "")
        }
    }

    private fun requestUnlock() {
        val km = getSystemService(KEYGUARD_SERVICE) as KeyguardManager
        if (!km.isDeviceSecure) {
            Toast.makeText(
                this,
                "Set a screen lock (PIN, pattern or fingerprint) in Android settings to open this locked site.",
                Toast.LENGTH_LONG,
            ).show()
            finish()
            return
        }
        @Suppress("DEPRECATION")
        val unlockIntent = km.createConfirmDeviceCredentialIntent(tile.name, "Unlock to open this site")
        if (unlockIntent == null) {
            finish()
            return
        }
        startActivityForResult(unlockIntent, REQ_UNLOCK)
    }

    private fun markUnlocked() {
        unlocked = true
        content.visibility = View.VISIBLE
        val w = web ?: return
        if (w.url == null) w.loadUrl(tile.url)
    }

    @Suppress("DEPRECATION")
    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        pausedAt = 0L
        when (requestCode) {
            REQ_UNLOCK -> if (resultCode == RESULT_OK) markUnlocked() else finish()
            REQ_FILE -> {
                fileCallback?.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(resultCode, data))
                fileCallback = null
            }
            else -> {}
        }
    }

    override fun onPause() {
        super.onPause()
        if (!::tile.isInitialized) return
        pausedAt = SystemClock.elapsedRealtime()
        web?.onPause()
        profile?.cookieManager?.flush()
    }

    override fun onResume() {
        super.onResume()
        if (!::tile.isInitialized) return
        web?.onResume()
        if (tile.locked && unlocked && pausedAt != 0L &&
            SystemClock.elapsedRealtime() - pausedAt > RELOCK_AFTER_MS
        ) {
            unlocked = false
            content.visibility = View.INVISIBLE
            requestUnlock()
        }
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun onBackPressed() {
        val w = web
        if (unlocked && w != null && w.canGoBack()) w.goBack() else finish()
    }

    override fun onDestroy() {
        fileCallback?.onReceiveValue(null)
        fileCallback = null
        web?.let { w ->
            w.stopLoading()
            (w.parent as? ViewGroup)?.removeView(w)
            w.destroy()
        }
        web = null
        super.onDestroy()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private companion object {
        const val REQ_UNLOCK = 1
        const val REQ_FILE = 2
        const val RELOCK_AFTER_MS = 60_000L
    }
}
