package com.uggdrg.flashshare

import com.jcraft.jsch.ChannelShell
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.Collections
import java.util.concurrent.atomic.AtomicReference

/**
 * 通过 SSH 反向隧道（localhost.run）把本机端口暴露成公网 https 网址。
 *
 * 国内网络下 22 端口的 SSH 常被限速或丢包，所以每一轮同时从 22 和 443 两个端口各发起一次连接，
 * 谁先拿到网址谁就是正式隧道，另一个自动关闭。奇数轮不申请 pty，偶数轮申请 pty。
 * 每一轮失败的真实原因都会显示在界面上，方便定位。
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

    private val sessions = Collections.synchronizedList(mutableListOf<Session>())
    private val logLines = ArrayDeque<String>()

    private val ports = listOf(22, 443)
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
        val copy = synchronized(sessions) { sessions.toList() }
        copy.forEach {
            try {
                it.disconnect()
            } catch (_: Exception) {
            }
        }
        thread?.interrupt()
    }

    private fun recentLog(): String = synchronized(logLines) { logLines.joinToString("\n") }

    private fun pause(ms: Long): Boolean {
        return try {
            Thread.sleep(ms)
            true
        } catch (e: InterruptedException) {
            false
        }
    }

    private fun loop() {
        var round = 0
        var everGotUrl = false
        val allErrors = mutableListOf<String>()
        while (running) {
            val usePty = ptyModes[round % ptyModes.size]
            onStatus(
                if (everGotUrl) "隧道断开，正在重连…"
                else "正在建立公网隧道…（第 ${round + 1} 轮，同时尝试 22 / 443 端口）"
            )
            synchronized(logLines) { logLines.clear() }

            val winner = AtomicReference<Session?>(null)
            val errs = Collections.synchronizedList(mutableListOf<String>())
            val threads = ports.map { port ->
                Thread({ runVariant(port, usePty, winner, errs) }, "tunnel-$port")
                    .also { it.isDaemon = true; it.start() }
            }
            try {
                threads.forEach { it.join() }
            } catch (e: InterruptedException) {
                return
            }
            if (!running) return

            if (winner.get() != null) {
                everGotUrl = true
                round = 0
                if (!pause(1000)) return
                continue
            }

            val msg = synchronized(errs) { errs.toList() }
            allErrors.addAll(msg)
            round++
            if (!everGotUrl && round >= 4) {
                onFatal(
                    "无法连接公网隧道服务（localhost.run）\n" +
                        allErrors.joinToString("\n") + "\n\n日志：\n" + recentLog()
                )
                return
            }
            onStatus("第 $round 轮失败，正在重试…\n" + msg.joinToString("\n"))
            if (!pause(1000)) return
        }
    }

    private fun runVariant(
        port: Int,
        usePty: Boolean,
        winner: AtomicReference<Session?>,
        errs: MutableList<String>
    ) {
        val label = "端口 $port（${if (usePty) "pty" else "无 pty"}）"
        var current: Session? = null
        try {
            val sess = JSch().getSession("nokey", "localhost.run", port)
            current = sess
            sessions.add(sess)
            sess.setConfig("StrictHostKeyChecking", "no")
            sess.setServerAliveInterval(30_000)
            sess.setServerAliveCountMax(3)
            sess.connect(15_000)
            if (!running || (winner.get() != null && winner.get() !== sess)) return
            sess.setPortForwardingR(80, "127.0.0.1", localPort)

            val ch = sess.openChannel("shell") as ChannelShell
            ch.setPty(usePty)
            val reader = BufferedReader(InputStreamReader(ch.inputStream))
            ch.connect(15_000)

            val urlRegex = Regex("https://[A-Za-z0-9-]+\\.lhr\\.life")
            var got = false
            while (running) {
                val line = reader.readLine() ?: break
                val m = urlRegex.find(line) ?: continue
                if (winner.compareAndSet(null, sess) || winner.get() === sess) {
                    got = true
                    onUrl(m.value)
                } else {
                    return // 另一个端口已经先成功，本连接退出
                }
            }
            if (!got) errs.add("$label：连接已建立，但服务器没有返回网址就关闭了")
        } catch (e: Exception) {
            if (running && (current == null || winner.get() !== current)) {
                errs.add("$label：${e.javaClass.simpleName}: ${e.message}")
            }
        } finally {
            current?.let {
                sessions.remove(it)
                try {
                    it.disconnect()
                } catch (_: Exception) {
                }
            }
        }
    }
}
