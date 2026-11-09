package com.uggdrg.flashshare

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.OpenableColumns
import android.provider.Settings
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.uggdrg.flashshare.databinding.ActivityMainBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding
    private val files = mutableListOf<SharedFile>()
    private val expiryOptions = listOf(
        "1 小时" to 3_600_000L,
        "6 小时" to 21_600_000L,
        "1 天" to 86_400_000L,
        "3 天" to 259_200_000L
    )
    private var expiryIndex = 1
    private var pendingStart = false
    private var currentUrl: String? = null

    private val picker =
        registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            addFiles(uris)
        }

    private val notifPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            if (pendingStart) {
                pendingStart = false
                startSharing()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)

        b.pickCard.setOnClickListener { picker.launch(arrayOf("*/*")) }
        b.expiryRow.setOnClickListener { chooseExpiry() }
        b.passwordSwitch.setOnCheckedChangeListener { _, on ->
            b.passwordInput.visibility = if (on) View.VISIBLE else View.GONE
        }
        b.startButton.setOnClickListener { onStartClicked() }
        b.stopButton.setOnClickListener {
            stopService(Intent(this, ShareService::class.java))
        }
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
                    putExtra(Intent.EXTRA_TEXT, "我给你分享了文件，用浏览器打开下载：$it")
                }
                startActivity(Intent.createChooser(send, "分享链接"))
            }
        }
        b.batteryButton.setOnClickListener {
            val i = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                .setData(Uri.parse("package:$packageName"))
            try {
                startActivity(i)
            } catch (e: Exception) {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            }
        }

        ShareState.status.observe(this) { render(it) }
        refreshFiles()
        refreshExpiry()
    }

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

    private fun describe(uri: Uri): SharedFile {
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
        val mime = contentResolver.getType(uri) ?: "application/octet-stream"
        return SharedFile(uri, name, size, mime)
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

    private fun onStartClicked() {
        if (files.isEmpty()) {
            Toast.makeText(this, "请先选择文件", Toast.LENGTH_SHORT).show()
            return
        }
        if (b.passwordSwitch.isChecked && b.passwordInput.text.isNullOrEmpty()) {
            Toast.makeText(this, "请输入密码", Toast.LENGTH_SHORT).show()
            return
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            pendingStart = true
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        startSharing()
    }

    private fun startSharing() {
        ShareState.files = files.toList()
        ShareState.status.value = ShareStatus.Starting
        val intent = Intent(this, ShareService::class.java).apply {
            putExtra(ShareService.EXTRA_DURATION_MS, expiryOptions[expiryIndex].second)
            if (b.passwordSwitch.isChecked) {
                putExtra(ShareService.EXTRA_PASSWORD, b.passwordInput.text.toString())
            }
        }
        ContextCompat.startForegroundService(this, intent)
    }

    private fun render(s: ShareStatus) {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        val needBattery = !pm.isIgnoringBatteryOptimizations(packageName)
        when (s) {
            is ShareStatus.Idle -> {
                currentUrl = null
                b.setupPanel.visibility = View.VISIBLE
                b.sharePanel.visibility = View.GONE
                b.errorText.visibility = View.GONE
            }
            is ShareStatus.Error -> {
                currentUrl = null
                b.setupPanel.visibility = View.VISIBLE
                b.sharePanel.visibility = View.GONE
                b.errorText.text = s.msg
                b.errorText.visibility = View.VISIBLE
            }
            is ShareStatus.Starting -> {
                b.setupPanel.visibility = View.GONE
                b.sharePanel.visibility = View.VISIBLE
                b.statusText.text = "正在启动…"
                b.urlText.text = ""
                setLinkButtons(false)
                b.batteryButton.visibility = View.GONE
            }
            is ShareStatus.Waiting -> {
                b.setupPanel.visibility = View.GONE
                b.sharePanel.visibility = View.VISIBLE
                b.statusText.text = s.msg
                setLinkButtons(false)
                b.batteryButton.visibility = if (needBattery) View.VISIBLE else View.GONE
            }
            is ShareStatus.Live -> {
                currentUrl = s.url
                b.setupPanel.visibility = View.GONE
                b.sharePanel.visibility = View.VISIBLE
                val until = SimpleDateFormat("M月d日 HH:mm", Locale.CHINA).format(Date(s.expiresAt))
                b.statusText.text = "分享中 · 有效至 $until\n请保持手机联网，对方下载完再停止"
                b.urlText.text = s.url
                setLinkButtons(true)
                b.batteryButton.visibility = if (needBattery) View.VISIBLE else View.GONE
            }
        }
    }

    private fun setLinkButtons(enabled: Boolean) {
        b.copyButton.isEnabled = enabled
        b.shareButton.isEnabled = enabled
    }
}
