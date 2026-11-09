package com.uggdrg.flashshare

import android.net.Uri
import androidx.lifecycle.MutableLiveData
import java.util.Locale

data class SharedFile(val uri: Uri, val name: String, val size: Long, val mime: String)

sealed class ShareStatus {
    object Idle : ShareStatus()
    object Starting : ShareStatus()
    data class Waiting(val msg: String) : ShareStatus()
    data class Live(val url: String, val expiresAt: Long) : ShareStatus()
    data class Error(val msg: String) : ShareStatus()
}

object ShareState {
    val status = MutableLiveData<ShareStatus>(ShareStatus.Idle)

    @Volatile
    var files: List<SharedFile> = emptyList()

    /** 本地服务器收到的 HTTP 请求数，用来判断公网链接有没有真正打通。 */
    val hits = MutableLiveData(0)
    private val hitCounter = java.util.concurrent.atomic.AtomicInteger(0)

    fun recordHit() {
        hits.postValue(hitCounter.incrementAndGet())
    }

    fun resetHits() {
        hitCounter.set(0)
        hits.postValue(0)
    }
}

fun formatSize(bytes: Long): String {
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
