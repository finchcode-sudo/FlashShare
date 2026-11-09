package com.uggdrg.flashshare

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.io.ByteArrayInputStream

/**
 * 参考 FilePizza（github.com/kern/filepizza）：
 * 发送方页面 = 一个普通网页，文件通过 WebRTC 从浏览器直连朋友的浏览器，
 * 中间只有 PeerJS 信令服务和 STUN，没有需要自己购买的服务器。
 * 所以这个 App 只做三件事：加载页面、让文件选择器能用、用前台服务保活。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var web: WebView
    private var fileCallback: ValueCallback<Array<Uri>>? = null

    private val chooser =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
            val uris = WebChromeClient.FileChooserParams.parseResult(r.resultCode, r.data)
            fileCallback?.onReceiveValue(uris)
            fileCallback = null
        }

    private val notifPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            startKeepAlive()
        }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        web = WebView(this)
        setContentView(web)

        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        }

        web.webViewClient = object : WebViewClient() {
            // FilePizza 默认用 Google 的 STUN，国内通常连不上。
            // 这里拦截页面对 /api/ice 的请求，换成国内可用的 STUN 列表。
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

            override fun onRenderProcessGone(
                view: WebView?,
                detail: android.webkit.RenderProcessGoneDetail?
            ): Boolean {
                // 渲染进程被系统杀掉时不要让整个 App 崩溃，重新加载页面
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
                fileCallback?.onReceiveValue(null)
                fileCallback = filePathCallback
                return try {
                    chooser.launch(params!!.createIntent())
                    true
                } catch (e: Exception) {
                    fileCallback = null
                    false
                }
            }
        }

        // 返回键：网页能后退就后退，否则把 App 放到后台（不退出，分享继续）
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (web.canGoBack()) web.goBack() else moveTaskToBack(true)
            }
        })

        if (savedInstanceState == null) web.loadUrl(HOME_URL) else web.restoreState(savedInstanceState)

        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            startKeepAlive()
        }
    }

    override fun onResume() {
        super.onResume()
        askBatteryExemptionOnce()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        web.saveState(outState)
    }

    private fun startKeepAlive() {
        ContextCompat.startForegroundService(this, Intent(this, ShareService::class.java))
    }

    /** 只在第一次打开时弹一次系统的"忽略电池优化"授权框，避免反复打扰。 */
    private fun askBatteryExemptionOnce() {
        val prefs = getSharedPreferences("flashshare", Context.MODE_PRIVATE)
        if (prefs.getBoolean("asked_battery", false)) return
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (pm.isIgnoringBatteryOptimizations(packageName)) return
        prefs.edit().putBoolean("asked_battery", true).apply()
        try {
            startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                    .setData(Uri.parse("package:$packageName"))
            )
        } catch (_: Exception) {
        }
    }

    companion object {
        private const val HOME_HOST = "file.pizza"
        private const val HOME_URL = "https://file.pizza/"

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
