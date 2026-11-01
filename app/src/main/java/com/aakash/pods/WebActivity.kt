package com.aakash.pods

import android.Manifest
import android.app.Activity
import android.app.ActivityManager
import android.app.AlertDialog
import android.app.KeyguardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.text.TextUtils
import android.util.Base64
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.PermissionRequest
import android.webkit.URLUtil
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
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

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

    private var pendingRequest: PermissionRequest? = null
    private var pendingResources: List<String> = emptyList()
    private val allowedThisSession = HashSet<String>()
    private val notifyTimes = ArrayDeque<Long>()

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

        if (tile.keepAlive) {
            KeepAlive.active.add(tile.id)
            KeepAlive.sync(this)
        }

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
        s.mediaPlaybackRequiresUserGesture = false
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

        installBridge(w)

        w.setDownloadListener { url, userAgent, contentDisposition, mimetype, _ ->
            onDownload(url, userAgent, contentDisposition, mimetype)
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

            override fun onPermissionRequest(request: PermissionRequest) {
                handlePermissionRequest(request)
            }

            override fun onPermissionRequestCanceled(request: PermissionRequest) {
                if (pendingRequest === request) pendingRequest = null
            }
        }
        return w
    }

    // ---- Page bridge: notifications and blob downloads, available only to this tile's own site ----

    private fun originOf(url: String): String {
        val u = Uri.parse(url)
        return if (u.port != -1) "${u.scheme}://${u.host}:${u.port}" else "${u.scheme}://${u.host}"
    }

    private fun installBridge(w: WebView) {
        val origin = originOf(tile.url)
        try {
            if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
                WebViewCompat.addWebMessageListener(
                    w,
                    "PodsBridge",
                    setOf(origin),
                    WebViewCompat.WebMessageListener { _, message, _, _, _ -> onBridgeMessage(message.data) },
                )
            }
            if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
                WebViewCompat.addDocumentStartJavaScript(w, NOTIFICATION_SHIM, setOf(origin))
            }
        } catch (e: Exception) {
            // Without the bridge the site still works; only notifications and blob downloads are lost.
        }
    }

    private fun allowNotify(): Boolean {
        val now = SystemClock.elapsedRealtime()
        while (notifyTimes.isNotEmpty() && now - notifyTimes.first() > 10_000L) notifyTimes.removeFirst()
        if (notifyTimes.size >= 10) return false
        notifyTimes.addLast(now)
        return true
    }

    private fun onBridgeMessage(raw: String?) {
        if (raw == null) return
        val o = try {
            JSONObject(raw)
        } catch (e: Exception) {
            return
        }
        when (o.optString("t")) {
            "notify" -> {
                if (allowNotify()) {
                    Notifier.post(
                        this,
                        tile,
                        o.optString("title").take(120),
                        o.optString("body").take(500),
                        o.optString("tag").take(80),
                    )
                }
            }
            "file" -> {
                val name = Downloads.cleanName(o.optString("name", "download"))
                val mime = o.optString("mime").ifEmpty { null }
                val b64 = o.optString("b64")
                Thread {
                    try {
                        val bytes = Base64.decode(b64, Base64.DEFAULT)
                        val where = Downloads.save(this@WebActivity, name, mime) { out -> out.write(bytes) }
                        runOnUiThread { toast("Saved to $where") }
                    } catch (e: Exception) {
                        runOnUiThread { toast("Download failed") }
                    }
                }.start()
            }
            else -> {}
        }
    }

    // ---- Downloads ----

    private fun onDownload(url: String, userAgent: String?, disposition: String?, mime: String?) {
        val name = Downloads.cleanName(URLUtil.guessFileName(url, disposition, mime))
        when {
            url.startsWith("blob:") -> {
                val js = BLOB_JS
                    .replace("__URL__", JSONObject.quote(url))
                    .replace("__NAME__", JSONObject.quote(name))
                    .replace("__MIME__", JSONObject.quote(mime ?: ""))
                web?.evaluateJavascript(js, null)
                toast("Preparing $name...")
            }
            url.startsWith("https://") -> downloadHttp(url, userAgent, name, mime)
            else -> toast("This kind of download isn't supported")
        }
    }

    private fun downloadHttp(url: String, userAgent: String?, name: String, mime: String?) {
        // Use this tile's own cookies, so downloads behind a login work.
        val cookie = profile?.cookieManager?.getCookie(url)
        val referer = tile.url
        toast("Downloading $name...")
        Thread {
            try {
                val c = URL(url).openConnection() as HttpURLConnection
                if (userAgent != null) c.setRequestProperty("User-Agent", userAgent)
                if (!cookie.isNullOrEmpty()) c.setRequestProperty("Cookie", cookie)
                c.setRequestProperty("Referer", referer)
                c.connectTimeout = 15_000
                c.readTimeout = 60_000
                if (c.responseCode !in 200..299) throw IOException("HTTP ${c.responseCode}")
                val where = Downloads.save(this@WebActivity, name, mime ?: c.contentType) { out ->
                    c.inputStream.use { it.copyTo(out) }
                }
                runOnUiThread { toast("Saved to $where") }
            } catch (e: Exception) {
                runOnUiThread { toast("Download failed: ${e.message}") }
            }
        }.start()
    }

    // ---- Camera and microphone ----

    private fun handlePermissionRequest(request: PermissionRequest) {
        val wanted = request.resources.filter {
            it == PermissionRequest.RESOURCE_VIDEO_CAPTURE || it == PermissionRequest.RESOURCE_AUDIO_CAPTURE
        }
        if (wanted.isEmpty()) {
            request.deny()
            return
        }
        val key = request.origin.toString() + wanted.sorted().joinToString(",")
        if (key in allowedThisSession) {
            grantWithAndroidPermission(request, wanted)
            return
        }
        val what = wanted.joinToString(" and ") {
            if (it == PermissionRequest.RESOURCE_VIDEO_CAPTURE) "camera" else "microphone"
        }
        val host = request.origin.host ?: tile.name
        AlertDialog.Builder(this)
            .setTitle("Allow $host?")
            .setMessage("This site wants to use your $what.")
            .setPositiveButton("Allow") { _, _ ->
                allowedThisSession.add(key)
                grantWithAndroidPermission(request, wanted)
            }
            .setNegativeButton("Block") { _, _ -> request.deny() }
            .setOnCancelListener { request.deny() }
            .show()
    }

    private fun grantWithAndroidPermission(request: PermissionRequest, wanted: List<String>) {
        val needed = wanted
            .map {
                if (it == PermissionRequest.RESOURCE_VIDEO_CAPTURE) Manifest.permission.CAMERA
                else Manifest.permission.RECORD_AUDIO
            }
            .filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (needed.isEmpty()) {
            request.grant(wanted.toTypedArray())
            return
        }
        pendingRequest?.deny()
        pendingRequest = request
        pendingResources = wanted
        requestPermissions(needed.toTypedArray(), REQ_PERM)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_PERM) return
        pausedAt = 0L
        val req = pendingRequest
        pendingRequest = null
        if (req == null) return
        if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
            req.grant(pendingResources.toTypedArray())
        } else {
            req.deny()
        }
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

    // ---- Screen lock ----

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
        // Sites that asked to stay connected keep running while the app is in the background.
        if (!tile.keepAlive) web?.onPause()
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
        when {
            unlocked && w != null && w.canGoBack() -> w.goBack()
            // Stay-connected sites go to the background instead of closing; use the X to close for real.
            unlocked && ::tile.isInitialized && tile.keepAlive -> moveTaskToBack(true)
            else -> finish()
        }
    }

    override fun onDestroy() {
        fileCallback?.onReceiveValue(null)
        fileCallback = null
        pendingRequest?.deny()
        pendingRequest = null
        web?.let { w ->
            w.stopLoading()
            (w.parent as? ViewGroup)?.removeView(w)
            w.destroy()
        }
        web = null
        if (::tile.isInitialized && tile.keepAlive) {
            KeepAlive.active.remove(tile.id)
            KeepAlive.sync(this)
        }
        super.onDestroy()
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private companion object {
        const val REQ_UNLOCK = 1
        const val REQ_FILE = 2
        const val REQ_PERM = 3
        const val RELOCK_AFTER_MS = 60_000L

        /** Turns the page's Notification API into Android notifications. */
        const val NOTIFICATION_SHIM = """
(function () {
  if (window.__podsShim) return;
  window.__podsShim = true;
  function send(o) {
    try { if (window.PodsBridge) window.PodsBridge.postMessage(JSON.stringify(o)); } catch (e) {}
  }
  function N(title, opts) {
    opts = opts || {};
    this.title = String(title);
    this.body = String(opts.body || '');
    this.tag = String(opts.tag || '');
    this.onclick = null; this.onclose = null; this.onerror = null; this.onshow = null;
    send({ t: 'notify', title: this.title, body: this.body, tag: this.tag });
    var self = this;
    setTimeout(function () { if (typeof self.onshow === 'function') { try { self.onshow(); } catch (e) {} } }, 0);
  }
  N.prototype.close = function () {};
  N.prototype.addEventListener = function () {};
  N.prototype.removeEventListener = function () {};
  N.prototype.dispatchEvent = function () { return true; };
  N.requestPermission = function (cb) {
    if (typeof cb === 'function') cb('granted');
    return Promise.resolve('granted');
  };
  Object.defineProperty(N, 'permission', { get: function () { return 'granted'; } });
  N.maxActions = 0;
  window.Notification = N;
})();
"""

        /** Reads a blob: URL inside the page and hands the bytes to the app. */
        const val BLOB_JS = """
(function () {
  var u = __URL__, n = __NAME__, m = __MIME__;
  fetch(u).then(function (r) { return r.blob(); }).then(function (b) {
    var fr = new FileReader();
    fr.onloadend = function () {
      var s = String(fr.result);
      var i = s.indexOf(',');
      try {
        window.PodsBridge.postMessage(JSON.stringify({ t: 'file', name: n, mime: m || b.type, b64: s.substring(i + 1) }));
      } catch (e) {}
    };
    fr.readAsDataURL(b);
  });
})();
"""
    }
}
