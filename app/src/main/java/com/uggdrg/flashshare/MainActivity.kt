package com.uggdrg.flashshare

import android.Manifest
import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.OpenableColumns
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.uggdrg.flashshare.databinding.ActivityMainBinding
import java.io.ByteArrayInputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import org.json.JSONObject
import org.json.JSONTokener

/**
 * 原生界面 + 藏在下面的 FilePizza 网页。
 *
 * 传输部分完全交给 FilePizza（github.com/kern/filepizza）：文件通过 WebRTC 从这个 App 里的网页
 * 直连朋友的浏览器，不需要自己的服务器。原生界面负责选文件、设置、显示链接，
 * 并通过 assets/engine.js 遥控下面的网页。
 */
class MainActivity : AppCompatActivity() {

    private enum class Phase { IDLE, STARTING, LIVE }

    private data class PickedFile(val uri: Uri, val name: String, val size: Long)

    private lateinit var b: ActivityMainBinding
    private lateinit var web: WebView
    private lateinit var backButton: Button
    private val handler = Handler(Looper.getMainLooper())

    private val files = mutableListOf<PickedFile>()
    private val expiryOptions = listOf(
        "1 小时" to 3_600_000L,
        "6 小时" to 21_600_000L,
        "1 天" to 86_400_000L,
        "3 天" to 259_200_000L
    )
    private var expiryIndex = 1

    private var phase = Phase.IDLE
    private var phaseSince = 0L
    private var lastTap = 0L
    private var awaitingChooser = false
    private var pwSet = false
    private var startClicked = false
    private var password = ""
    private var expireAt = 0L
    private var currentUrl: String? = null
    private var linkIsFallback = false
    private var pageError: String? = null

    private val engineScript: String by lazy {
        assets.open("engine.js").bufferedReader(Charsets.UTF_8).use { it.readText() }
    }

    private val picker =
        registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            addFiles(uris)
        }

    private val notifPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        web = WebView(this)
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        }
        web.webViewClient = object : WebViewClient() {
            // FilePizza 默认用 Google 的 STUN，国内通常连不上，换成国内可用的 STUN 列表
            override fun shouldInterceptRequest(
                view: WebView?,
                request: WebResourceRequest?
            ): WebResourceResponse? {
                val url = request?.url
                if (url != null && url.path == "/api/ice" && url.host == HOME_HOST) {
                    return WebResourceResponse(
                        "application/json", "utf-8", 200, "OK",
                        mapOf("Cache-Control" to "no-store"),
                        ByteArrayInputStream(ICE_JSON.toByteArray(Charsets.UTF_8))
                    )
                }
                return super.shouldInterceptRequest(view, request)
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                super.onPageStarted(view, url, favicon)
                pageError = null
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                injectEngine()
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?
            ) {
                super.onReceivedError(view, request, error)
                if (request?.isForMainFrame == true) {
                    pageError = "打不开 file.pizza（${error?.description}），请检查网络"
                }
            }

            override fun onRenderProcessGone(
                view: WebView?,
                detail: android.webkit.RenderProcessGoneDetail?
            ): Boolean {
                web.loadUrl(HOME_URL)
                return true
            }
        }
        web.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                params: FileChooserParams?
            ): Boolean {
                // 只在我们自己合成的点击之后才把选好的文件交给网页，其他情况一律取消
                if (awaitingChooser && files.isNotEmpty()) {
                    awaitingChooser = false
                    filePathCallback?.onReceiveValue(files.map { it.uri }.toTypedArray())
                } else {
                    filePathCallback?.onReceiveValue(null)
                }
                return true
            }
        }

        // 网页放在最底层（保持"可见"状态，浏览器才会正常处理输入），原生界面不透明地盖在上面
        val root = FrameLayout(this)
        root.addView(
            web,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )
        b = ActivityMainBinding.inflate(layoutInflater)
        root.addView(
            b.root,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )
        backButton = Button(this).apply {
            text = "返回"
            visibility = View.GONE
            setOnClickListener { showDebug(false) }
        }
        root.addView(
            backButton,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.END
            )
        )
        setContentView(root)

        b.pickCard.setOnClickListener { picker.launch(arrayOf("*/*")) }
        b.expiryRow.setOnClickListener { chooseExpiry() }
        b.passwordSwitch.setOnCheckedChangeListener { _, on ->
            b.passwordInput.visibility = if (on) View.VISIBLE else View.GONE
        }
        b.startButton.setOnClickListener { onStartClicked() }
        b.stopButton.setOnClickListener { stopSharing(null) }
        b.copyButton.setOnClickListener {
            currentUrl?.let {
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("link", it))
                Toast.makeText(this, "已复制", Toast.LENGTH_SHORT).show()
            }
        }
        b.shareButton.setOnClickListener {
            currentUrl?.let {
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    val text = if (linkIsFallback) {
                        "我给你分享了文件，用浏览器打开下面的链接：\n$it\n\n" +
                            "页面是英文的，点中间绿色的 Download 按钮就能下载。" +
                            "下载期间我这边要保持在线，请尽快下载。\n" +
                            "如果提示要输入密码（Password），向我要。"
                    } else {
                        "我给你分享了文件，用浏览器打开下面的链接，点「开始下载」就行：\n$it\n\n" +
                            "下载期间我这边要保持在线，请尽快下载。" +
                            if (password.isNotEmpty()) "\n需要密码的话向我要。" else ""
                    }
                    putExtra(Intent.EXTRA_TEXT, text)
                }
                startActivity(Intent.createChooser(send, "分享链接"))
            }
        }
        b.batteryButton.setOnClickListener { openBatterySettings() }
        b.debugToggle.setOnClickListener { showDebug(true) }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (b.root.visibility != View.VISIBLE) showDebug(false) else moveTaskToBack(true)
            }
        })

        refreshFiles()
        refreshExpiry()
        showSetup(null)
        web.loadUrl(HOME_URL)

        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    override fun onResume() {
        super.onResume()
        askBatteryExemptionOnce()
        updateBatteryButton()
    }

    // ---------------------------------------------------------------- 选文件和设置

    private fun addFiles(uris: List<Uri>) {
        for (uri in uris) {
            try {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: Exception) {
            }
            if (files.none { it.uri == uri }) files.add(describe(uri))
        }
        refreshFiles()
    }

    private fun describe(uri: Uri): PickedFile {
        var name = "file"
        var size = -1L
        contentResolver.query(uri, null, null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val si = c.getColumnIndex(OpenableColumns.SIZE)
                if (ni >= 0) name = c.getString(ni) ?: name
                if (si >= 0 && !c.isNull(si)) size = c.getLong(si)
            }
        }
        return PickedFile(uri, name, size)
    }

    private fun refreshFiles() {
        if (files.isEmpty()) {
            b.fileSummary.text = "尚未选择"
            b.fileList.text = ""
        } else {
            val total = files.sumOf { maxOf(it.size, 0L) }
            b.fileSummary.text = "已选 ${files.size} 个文件 · ${formatSize(total)}"
            b.fileList.text = files.joinToString("\n") { "• ${it.name}（${formatSize(it.size)}）" }
        }
    }

    private fun formatSize(bytes: Long): String {
        if (bytes < 0) return "未知大小"
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        var v = bytes.toDouble()
        var i = 0
        while (v >= 1024 && i < units.size - 1) {
            v /= 1024
            i++
        }
        return if (i == 0) "$bytes B" else String.format(Locale.US, "%.1f %s", v, units[i])
    }

    private fun chooseExpiry() {
        AlertDialog.Builder(this)
            .setTitle("过期时间")
            .setItems(expiryOptions.map { it.first }.toTypedArray()) { _, which ->
                expiryIndex = which
                refreshExpiry()
            }
            .show()
    }

    private fun refreshExpiry() {
        b.expiryValue.text = "${expiryOptions[expiryIndex].first} ›"
    }

    // ---------------------------------------------------------------- 开始 / 停止

    private fun onStartClicked() {
        if (files.isEmpty()) {
            Toast.makeText(this, "请先选择文件", Toast.LENGTH_SHORT).show()
            return
        }
        if (b.passwordSwitch.isChecked && b.passwordInput.text.isNullOrEmpty()) {
            Toast.makeText(this, "请输入密码", Toast.LENGTH_SHORT).show()
            return
        }
        password = if (b.passwordSwitch.isChecked) b.passwordInput.text.toString() else ""
        expireAt = System.currentTimeMillis() + expiryOptions[expiryIndex].second

        if (pageError != null) {
            pageError = null
            web.reload()
        }

        phase = Phase.STARTING
        phaseSince = System.currentTimeMillis()
        lastTap = 0L
        awaitingChooser = false
        pwSet = false
        startClicked = false
        currentUrl = null

        showSharing("正在准备…")
        ContextCompat.startForegroundService(this, Intent(this, ShareService::class.java))
        handler.removeCallbacks(tick)
        handler.post(tick)
    }

    /** message 不为空时显示在设置页上，用来告诉用户为什么停了。 */
    private fun stopSharing(message: String?) {
        handler.removeCallbacks(tick)
        if (phase != Phase.IDLE) {
            web.evaluateJavascript("window.__fs && window.__fs.stop()", null)
        }
        phase = Phase.IDLE
        awaitingChooser = false
        currentUrl = null
        stopService(Intent(this, ShareService::class.java))
        // 页面里的文件列表在停止后还会保留，重新加载一次最干净
        handler.postDelayed({ web.reload() }, 400)
        showSetup(message)
    }

    private fun fail(message: String) = stopSharing(message)

    // ---------------------------------------------------------------- 遥控网页

    private val tick = object : Runnable {
        override fun run() {
            if (phase == Phase.IDLE) return
            if (System.currentTimeMillis() >= expireAt) {
                stopSharing("已到设定的过期时间，分享已停止")
                return
            }
            val err = pageError
            if (err != null && phase == Phase.STARTING) {
                fail(err)
                return
            }
            web.evaluateJavascript("window.__fs ? JSON.stringify(window.__fs.state()) : null") { v ->
                if (phase != Phase.IDLE) onState(parseJson(v))
            }
            handler.postDelayed(this, 800)
        }
    }

    private fun onState(s: JSONObject?) {
        val now = System.currentTimeMillis()
        if (s == null) {
            // 页面还没准备好，或脚本没注入上
            injectEngine()
            if (phase == Phase.STARTING && now - phaseSince > 45_000) {
                fail("网页一直没有加载出来，请确认手机能打开 https://file.pizza")
            }
            return
        }
        val stage = s.optString("stage")
        val error = s.optString("error")
        if (error.isNotEmpty()) {
            fail("页面报错：$error")
            return
        }

        when (phase) {
            Phase.STARTING -> {
                when (stage) {
                    "idle" -> {
                        if (startClicked) {
                            fail("页面意外回到了初始状态，请重试")
                            return
                        }
                        setStatus("正在选择文件…")
                        if (now - lastTap > 4_000) {
                            lastTap = now
                            tapDropZone()
                        }
                    }
                    "confirm" -> {
                        setStatus("正在开始…")
                        if (!pwSet) {
                            pwSet = true
                            if (password.isNotEmpty()) {
                                web.evaluateJavascript(
                                    "window.__fs.setPassword(${JSONObject.quote(password)})", null
                                )
                            }
                        } else if (!startClicked) {
                            startClicked = true
                            web.evaluateJavascript("window.__fs.start()", null)
                        }
                    }
                    "live" -> {
                        phase = Phase.LIVE
                        updateLive(s)
                        return
                    }
                    else -> setStatus("正在连接中转服务…（首次可能要十几秒）")
                }
                if (now - phaseSince > 60_000) {
                    fail(
                        if (startClicked) "连接中转服务超时（0.peerjs.com 可能无法访问），请换个网络重试"
                        else "没能把文件交给网页，请重试"
                    )
                }
            }
            Phase.LIVE -> {
                if (stage == "live") updateLive(s)
                else if (stage == "idle" || stage == "confirm") fail("连接已中断，请重新开始分享")
            }
            Phase.IDLE -> {}
        }
    }

    /** 合成一次真实触摸点在"选择文件"按钮上，这样网页才被允许弹出文件选择器。 */
    private fun tapDropZone() {
        web.evaluateJavascript(
            "window.__fs ? JSON.stringify(window.__fs.rect('#drop-zone-button')) : null"
        ) { v ->
            val r = parseJson(v) ?: return@evaluateJavascript
            val dpr = r.optDouble("dpr", 1.0)
            val x = (r.optDouble("x") * dpr).toFloat()
            val y = (r.optDouble("y") * dpr).toFloat()
            awaitingChooser = true
            val down = SystemClock.uptimeMillis()
            val d = MotionEvent.obtain(down, down, MotionEvent.ACTION_DOWN, x, y, 0)
            web.dispatchTouchEvent(d)
            d.recycle()
            handler.postDelayed({
                val up = MotionEvent.obtain(down, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, x, y, 0)
                web.dispatchTouchEvent(up)
                up.recycle()
            }, 60)
        }
    }

    private fun injectEngine() {
        web.evaluateJavascript(engineScript, null)
    }

    private fun parseJson(v: String?): JSONObject? {
        if (v == null || v == "null") return null
        return try {
            val o = JSONTokener(v).nextValue()
            if (o is String) JSONObject(o) else null
        } catch (e: Exception) {
            null
        }
    }

    // ---------------------------------------------------------------- 界面显示

    private fun showSetup(message: String?) {
        b.setupPanel.visibility = View.VISIBLE
        b.sharePanel.visibility = View.GONE
        if (message.isNullOrEmpty()) {
            b.errorText.visibility = View.GONE
        } else {
            b.errorText.text = message
            b.errorText.visibility = View.VISIBLE
        }
    }

    private fun showSharing(status: String) {
        b.setupPanel.visibility = View.GONE
        b.sharePanel.visibility = View.VISIBLE
        b.statusText.text = status
        b.urlText.text = ""
        b.hitText.text = ""
        b.copyButton.isEnabled = false
        b.shareButton.isEnabled = false
        updateBatteryButton()
    }

    private fun setStatus(text: String) {
        if (phase == Phase.STARTING) b.statusText.text = text
    }

    private fun updateLive(s: JSONObject) {
        val shortUrl = s.optString("shortUrl")
        if (shortUrl.isEmpty()) return
        // 优先给朋友自己的中文下载页（把上传端的 PeerJS ID 放在 # 后面）；
        // 没拿到 ID 时退回 FilePizza 的英文链接
        val peerId = s.optString("peerId")
        linkIsFallback = true
        val url = if (linkIsFallback) shortUrl else "$DOWNLOAD_PAGE#$peerId"
        currentUrl = url
        val until = SimpleDateFormat("M月d日 HH:mm", Locale.CHINA).format(Date(expireAt))
        b.statusText.text = "分享中 · 有效至 $until\n请保持手机联网、不要关闭 App，对方下载完再停止"
        b.urlText.text = url
        b.copyButton.isEnabled = true
        b.shareButton.isEnabled = true
        val total = s.optInt("total", 0)
        val active = s.optInt("active", 0)
        val count = if (total == 0) "等待朋友打开链接…" else "已连接 $total 人 · 正在下载 $active 人"
        b.hitText.text = if (linkIsFallback) count else "$count\n备用链接（英文页面）：$shortUrl"
    }

    private fun showDebug(show: Boolean) {
        b.root.visibility = if (show) View.GONE else View.VISIBLE
        backButton.visibility = if (show) View.VISIBLE else View.GONE
    }

    // ---------------------------------------------------------------- 电池优化

    private fun updateBatteryButton() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        val need = !pm.isIgnoringBatteryOptimizations(packageName)
        b.batteryButton.visibility = if (need && b.sharePanel.visibility == View.VISIBLE) View.VISIBLE else View.GONE
    }

    private fun openBatterySettings() {
        try {
            startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                    .setData(Uri.parse("package:$packageName"))
            )
        } catch (e: Exception) {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }

    /** 只在第一次打开时弹一次系统的"忽略电池优化"授权框，避免反复打扰。 */
    private fun askBatteryExemptionOnce() {
        val prefs = getSharedPreferences("flashshare", Context.MODE_PRIVATE)
        if (prefs.getBoolean("asked_battery", false)) return
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (pm.isIgnoringBatteryOptimizations(packageName)) return
        prefs.edit().putBoolean("asked_battery", true).apply()
        openBatterySettings()
    }

    companion object {
        private const val HOME_HOST = "file.pizza"
        private const val HOME_URL = "https://file.pizza/"

        // 朋友打开的中文下载页（site/index.html，由 GitHub Pages 发布）
        private const val DOWNLOAD_PAGE = "https://finchcode-sudo.github.io/FlashShare/"

        // 结构和 FilePizza 的 /api/ice 返回值一致（见其 src/app/api/ice/route.ts）：
        // host/path 是 PeerJS 信令服务默认值，iceServers 换成多个 STUN。
        private const val ICE_JSON =
            "{\"host\":\"0.peerjs.com\",\"path\":\"/\",\"iceServers\":[" +
                "{\"urls\":\"stun:stun.miwifi.com:3478\"}," +
                "{\"urls\":\"stun:stun.chat.bilibili.com:3478\"}," +
                "{\"urls\":\"stun:stun.cloudflare.com:3478\"}," +
                "{\"urls\":\"stun:stun.l.google.com:19302\"}]}"
    }
}
