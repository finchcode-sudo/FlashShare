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

    fun start() {
        running = true
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

    private fun loop() {
        var failuresWithoutUrl = 0
        var everGotUrl = false
        while (running) {
            var gotUrl = false
            try {
                onStatus(if (everGotUrl) "隧道断开，正在重连…" else "正在建立公网隧道…")
                val s = JSch().getSession("nokey", "localhost.run", 22)
                s.setConfig("StrictHostKeyChecking", "no")
                // 避开 Android 上可能不被支持的算法，优先用通用的
                s.setConfig("kex", "ecdh-sha2-nistp256,ecdh-sha2-nistp384,diffie-hellman-group14-sha256,diffie-hellman-group-exchange-sha256")
                s.setConfig("server_host_key", "rsa-sha2-512,rsa-sha2-256,ecdsa-sha2-nistp256,ssh-rsa")
                s.setServerAliveInterval(30_000)
                s.setServerAliveCountMax(3)
                s.connect(15_000)
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
            } catch (e: InterruptedException) {
                return
            } catch (e: Exception) {
                // 下面统一处理重试
            } finally {
                try {
                    session?.disconnect()
                } catch (_: Exception) {
                }
            }
            if (!running) return
            if (!gotUrl) {
                failuresWithoutUrl++
                if (!everGotUrl && failuresWithoutUrl >= 4) {
                    onFatal("无法连接公网隧道服务（localhost.run），请检查网络后重试")
                    return
                }
            }
            try {
                Thread.sleep(3000)
            } catch (e: InterruptedException) {
                return
            }
        }
    }
}
