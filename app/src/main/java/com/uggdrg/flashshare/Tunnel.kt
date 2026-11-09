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
 * 连接失败时会轮换不同的算法配置重试，并把真实的失败原因交给 onFatal。
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

    // 依次尝试：1) 库的默认算法  2) 只用 NIST 曲线 + RSA/ECDSA 主机密钥
    private val configs: List<Map<String, String>> = listOf(
        emptyMap(),
        mapOf(
            "kex" to "ecdh-sha2-nistp256,ecdh-sha2-nistp384,ecdh-sha2-nistp521,diffie-hellman-group14-sha256,diffie-hellman-group-exchange-sha256",
            "server_host_key" to "rsa-sha2-512,rsa-sha2-256,ecdsa-sha2-nistp256,ecdsa-sha2-nistp384,ssh-rsa"
        )
    )

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
            try {
                onStatus(if (everGotUrl) "隧道断开，正在重连…" else "正在建立公网隧道…（第 ${attempt + 1} 次）")
                synchronized(logLines) { logLines.clear() }
                val s = JSch().getSession("nokey", "localhost.run", 22)
                s.setConfig("StrictHostKeyChecking", "no")
                configs[attempt % configs.size].forEach { (k, v) -> s.setConfig(k, v) }
                s.setServerAliveInterval(30_000)
                s.setServerAliveCountMax(3)
                s.connect(10_000)
                session = s
                s.setPortForwardingR(80, "127.0.0.1", localPort)

                val ch = s.openChannel("shell") as ChannelShell
                ch.setPty(true)
                val reader = BufferedReader(InputStreamReader(ch.inputStream))
                ch.connect(10_000)

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
                lastError = "${e.javaClass.simpleName}: ${e.message}"
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
                if (!everGotUrl && failuresWithoutUrl >= configs.size * 2) {
                    onFatal(
                        "无法连接公网隧道服务（localhost.run）\n原因：$lastError\n\n日志：\n${recentLog()}"
                    )
                    return
                }
            }
            try {
                Thread.sleep(2000)
            } catch (e: InterruptedException) {
                return
            }
        }
    }
}
