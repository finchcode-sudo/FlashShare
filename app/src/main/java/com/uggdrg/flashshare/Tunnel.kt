package com.uggdrg.flashshare

import com.jcraft.jsch.ChannelShell
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * 通过 SSH 反向隧道（localhost.run）把本机端口暴露成公网 https 网址。
 * 不需要账号，也不需要在手机里放额外的二进制文件。
 * 断线会自动重连；重连后网址可能变化，会再次回调 onUrl。
 *
 * 密钥交换和主机密钥校验已确认可用；上一版卡在 shell/pty 的通道请求没有回复，
 * 所以这里轮换「不申请 pty」和「申请 pty」两种方式重试，并把真实失败原因交给 onFatal。
 */
class Tunnel(
    private val localPort: Int,
    private val onUrl: (String) -> Unit,
    private val onStatus: (String) -> Unit,
    private val onFatal: (String) -> Unit
) {
    @Volatile
    private var running = false
    private var thread: Thread? = null

    @Volatile
    private var session: Session? = null

    private val logLines = ArrayDeque<String>()

    // 依次尝试：1) 不申请 pty  2) 申请 pty
    private val ptyModes = listOf(false, true)

    fun start() {
        running = true
        JSch.setLogger(object : com.jcraft.jsch.Logger {
            override fun isEnabled(level: Int) = true
            override fun log(level: Int, message: String?) {
                synchronized(logLines) {
                    logLines.addLast(message ?: "")
                    while (logLines.size > 14) logLines.removeFirst()
                }
            }
        })
        thread = Thread({ loop() }, "tunnel").also { it.isDaemon = true; it.start() }
    }

    fun stop() {
        running = false
        try {
            session?.disconnect()
        } catch (_: Exception) {
        }
        thread?.interrupt()
    }

    private fun recentLog(): String = synchronized(logLines) { logLines.joinToString("\n") }

    private fun loop() {
        var attempt = 0
        var everGotUrl = false
        var failuresWithoutUrl = 0
        var lastError = ""
        while (running) {
            var gotUrl = false
            val usePty = ptyModes[attempt % ptyModes.size]
            try {
                onStatus(
                    if (everGotUrl) "隧道断开，正在重连…"
                    else "正在建立公网隧道…（第 ${attempt + 1} 次，${if (usePty) "pty" else "无 pty"}）"
                )
                synchronized(logLines) { logLines.clear() }
                val s = JSch().getSession("nokey", "localhost.run", 22)
                s.setConfig("StrictHostKeyChecking", "no")
                s.setServerAliveInterval(30_000)
                s.setServerAliveCountMax(3)
                s.connect(8_000)
                session = s
                s.setPortForwardingR(80, "127.0.0.1", localPort)

                val ch = s.openChannel("shell") as ChannelShell
                ch.setPty(usePty)
                val reader = BufferedReader(InputStreamReader(ch.inputStream))
                ch.connect(8_000)

                val urlRegex = Regex("https://[A-Za-z0-9-]+\\.lhr\\.life")
                while (running) {
                    val line = reader.readLine() ?: break
                    val m = urlRegex.find(line)
                    if (m != null) {
                        gotUrl = true
                        everGotUrl = true
                        failuresWithoutUrl = 0
                        onUrl(m.value)
                    }
                }
                if (!gotUrl) lastError = "连接已建立，但服务器没有返回网址就关闭了"
            } catch (e: InterruptedException) {
                return
            } catch (e: Exception) {
                lastError = "${e.javaClass.simpleName}: ${e.message}（${if (usePty) "pty" else "无 pty"}）"
            } finally {
                try {
                    session?.disconnect()
                } catch (_: Exception) {
                }
            }
            if (!running) return
            if (!gotUrl) {
                failuresWithoutUrl++
                attempt++
                if (!everGotUrl && failuresWithoutUrl >= ptyModes.size * 2) {
                    onFatal(
                        "无法连接公网隧道服务（localhost.run）\n原因：$lastError\n\n日志：\n${recentLog()}"
                    )
                    return
                }
            }
            try {
                Thread.sleep(1000)
            } catch (e: InterruptedException) {
                return
            }
        }
    }
}
