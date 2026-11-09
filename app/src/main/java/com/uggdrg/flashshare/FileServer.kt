package com.uggdrg.flashshare

import android.content.Context
import android.os.ParcelFileDescriptor
import android.text.TextUtils
import android.util.Base64
import fi.iki.elonen.NanoHTTPD
import java.io.FileInputStream
import java.net.URLEncoder
import java.security.MessageDigest

/** 只监听 127.0.0.1，由隧道转发到公网，局域网无法直接访问。 */
class FileServer(
    port: Int,
    private val ctx: Context,
    private val files: List<SharedFile>,
    private val password: String?
) : NanoHTTPD("127.0.0.1", port) {

    override fun serve(session: IHTTPSession): Response {
        ShareState.recordHit()
        return try {
            if (password != null && !authorized(session)) {
                return newFixedLengthResponse(
                    Response.Status.UNAUTHORIZED, "text/plain; charset=utf-8", "需要密码"
                ).apply { addHeader("WWW-Authenticate", "Basic realm=\"FlashShare\"") }
            }
            val uri = session.uri
            when {
                uri == "/" -> page()
                uri.startsWith("/f/") -> {
                    val index = uri.removePrefix("/f/").toIntOrNull()
                    if (index == null) notFound() else serveFile(index, session.headers["range"])
                }
                else -> notFound()
            }
        } catch (e: Exception) {
            newFixedLengthResponse(
                Response.Status.INTERNAL_ERROR, "text/plain; charset=utf-8", "服务出错"
            )
        }
    }

    private fun notFound() =
        newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain; charset=utf-8", "未找到")

    private fun authorized(session: IHTTPSession): Boolean {
        val header = session.headers["authorization"] ?: return false
        if (!header.startsWith("Basic ", ignoreCase = true)) return false
        val decoded = try {
            String(Base64.decode(header.substring(6).trim(), Base64.DEFAULT), Charsets.UTF_8)
        } catch (e: IllegalArgumentException) {
            return false
        }
        val given = decoded.substringAfter(':', "")
        return MessageDigest.isEqual(given.toByteArray(), password!!.toByteArray())
    }

    private fun page(): Response {
        val sb = StringBuilder()
        sb.append(
            """<!doctype html><html lang="zh"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1"><title>闪传</title>
<style>
body{margin:0;background:#f5f6f8;font-family:-apple-system,"PingFang SC","Microsoft YaHei",sans-serif;color:#111}
main{max-width:560px;margin:0 auto;padding:24px 16px}
h1{font-size:22px;margin:8px 0 16px}
a.file{display:flex;justify-content:space-between;gap:12px;align-items:center;background:#fff;
border-radius:14px;padding:16px;margin-bottom:10px;text-decoration:none;color:inherit}
.n{word-break:break-all;font-size:16px}.s{color:#888;font-size:13px;white-space:nowrap}
.d{color:#3668e8;font-size:14px;white-space:nowrap}
p{color:#888;font-size:13px}
</style></head><body><main><h1>朋友给你分享了文件</h1>"""
        )
        files.forEachIndexed { i, f ->
            sb.append("<a class=\"file\" href=\"/f/").append(i).append("\" download>")
            sb.append("<span class=\"n\">").append(TextUtils.htmlEncode(f.name)).append("</span>")
            sb.append("<span class=\"s\">").append(formatSize(f.size)).append("</span>")
            sb.append("<span class=\"d\">下载</span></a>")
        }
        sb.append("<p>文件直接来自对方手机，对方关闭分享后链接即失效。</p></main></body></html>")
        return newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", sb.toString())
            .apply { addHeader("Cache-Control", "no-store") }
    }

    private fun serveFile(index: Int, rangeHeader: String?): Response {
        val f = files.getOrNull(index) ?: return notFound()
        val pfd: ParcelFileDescriptor = ctx.contentResolver.openFileDescriptor(f.uri, "r")
            ?: return notFound()
        val total = if (pfd.statSize >= 0) pfd.statSize else f.size
        if (total <= 0) {
            pfd.close()
            return newFixedLengthResponse(Response.Status.OK, f.mime, "").apply {
                addHeader("Content-Disposition", disposition(f.name))
            }
        }

        var start = 0L
        var end = total - 1
        var partial = false
        if (rangeHeader != null && rangeHeader.startsWith("bytes=") && !rangeHeader.contains(",")) {
            val spec = rangeHeader.substring(6).trim()
            val dash = spec.indexOf('-')
            if (dash >= 0) {
                val a = spec.substring(0, dash)
                val e = spec.substring(dash + 1)
                try {
                    if (a.isEmpty()) {
                        start = (total - e.toLong()).coerceAtLeast(0)
                    } else {
                        start = a.toLong()
                        if (e.isNotEmpty()) end = minOf(e.toLong(), total - 1)
                    }
                    partial = true
                } catch (ex: NumberFormatException) {
                    start = 0L
                    end = total - 1
                    partial = false
                }
            }
        }
        if (partial && (start > end || start >= total)) {
            pfd.close()
            return newFixedLengthResponse(
                Response.Status.RANGE_NOT_SATISFIABLE, "text/plain; charset=utf-8", ""
            ).apply { addHeader("Content-Range", "bytes */$total") }
        }

        val stream = ParcelFileDescriptor.AutoCloseInputStream(pfd)
        if (start > 0) (stream as FileInputStream).channel.position(start)
        val length = end - start + 1
        val status = if (partial) Response.Status.PARTIAL_CONTENT else Response.Status.OK
        return newFixedLengthResponse(status, f.mime, stream, length).apply {
            addHeader("Accept-Ranges", "bytes")
            addHeader("Content-Disposition", disposition(f.name))
            if (partial) addHeader("Content-Range", "bytes $start-$end/$total")
        }
    }

    private fun disposition(name: String): String {
        val ascii = name.map { if (it.code in 32..126 && it != '"' && it != '\\') it else '_' }
            .joinToString("")
        val encoded = URLEncoder.encode(name, "UTF-8").replace("+", "%20")
        return "attachment; filename=\"$ascii\"; filename*=UTF-8''$encoded"
    }
}
