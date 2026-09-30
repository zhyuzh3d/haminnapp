package life.airen.haminn

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import life.airen.haminn.capability.NativeHttpClient
import life.airen.haminn.data.FileStore
import life.airen.haminn.model.ErrorCodes
import life.airen.haminn.model.HaminnException
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/**
 * 连接复用:同一个目标连续几次请求,只许建立**一条** TCP 连接。
 *
 * 2026-09-30 现场:页面按 700ms 的节奏轮询同一个接口(110 次),5 次读超时、每次
 * 约 15 秒,最后一次把整轮生成判死。原来 `client()` 每个请求都
 * `OkHttpClient.Builder()` 新建一个客户端,**而连接池是客户端自带的** ——
 * 每个新客户端带一个空池,于是每次调用都是一条全新 TCP 连接。服务端(ComfyUI)
 * 生成时本来就忙,还要每 700ms 多接一条新连接。
 *
 * 判据打在**服务端侧实际 accept 了几条连接**上:读代码看不出池子有没有命中,
 * 数 accept 才看得出。
 *
 * 两条用例:
 *  1. 连续三次请求 ⇒ 只许 1 条连接、服务端收到 3 次请求;
 *  2. 连接被服务端悄悄关掉之后的那一次请求 ⇒ 页面拿到的必须是一次成功、或者一个
 *     **能重试**的网络错误,而且不许挂到读超时以外 —— 这是复用引入的新边角
 *     (池子里那条连接其实已经死了),它的表现必须是页面侧看得懂、能重试的。
 *
 * 把池子改回「每个请求一个客户端」、或者把钉定 DNS 改回匿名 lambda
 * (相等性=身份,每个请求算出的 Address 都不同)会让用例 1 变红。
 */
@RunWith(AndroidJUnit4::class)
class NativeHttpConnectionReuseInstrumentedTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    /* 探针只能绑在**站点本地**地址上:127.0.0.1 会被 resolveAndValidate 以 ORIGIN_DENIED
       挡掉(那条规则由 PersistenceInstrumentedTest.nativeHttpRejectsLoopbackBeforeAuthorization 守着)。
       关掉 Wi-Fi 时这里会带着明确理由失败,而不是静默跳过。 */
    private fun siteLocalAddress(): String =
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { runCatching { it.isUp }.getOrDefault(false) }
            .filter { runCatching { it.isLoopback }.getOrDefault(true).not() }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .firstOrNull { it.isSiteLocalAddress }
            ?.hostAddress
            ?: error("本机没有站点本地 IPv4 地址(关掉 Wi-Fi 就无从下手),连接复用在没网的状态下验不了")

    /**
     * 一个支持 keep-alive 的探针服务器:每条连接上连续服务多个请求,
     * 并分别记录连接数与请求数。
     *
     * @param closeAfterResponses 服务端在第 N 次响应之后主动关掉连接 ——
     *        用来制造「池子里那条连接其实已经死了」的那个边角。
     */
    private inner class Probe(private val closeAfterResponses: Int = Int.MAX_VALUE) : AutoCloseable {
        val accepts = AtomicInteger(0)
        val requests = AtomicInteger(0)
        private val server: ServerSocket
        val url: String

        init {
            val address = siteLocalAddress()
            server = ServerSocket(0, 8, InetAddress.getByName(address))
            url = "http://$address:${server.localPort}/probe"
            val acceptor = Thread {
                while (!server.isClosed) {
                    val socket = try {
                        server.accept()
                    } catch (ignored: Throwable) {
                        return@Thread
                    }
                    accepts.incrementAndGet()
                    Thread { serve(socket) }.apply { isDaemon = true }.start()
                }
            }
            acceptor.isDaemon = true
            acceptor.start()
        }

        private fun serve(socket: Socket) {
            try {
                socket.use { open ->
                    val input = open.getInputStream().bufferedReader()
                    val output = open.getOutputStream()
                    while (true) {
                        /* 请求行 + 头,读到空行为止。GET 没有正文,所以这里不会把
                           下一次请求的开头吞掉。 */
                        val requestLine = input.readLine() ?: return
                        if (requestLine.isEmpty()) continue
                        while (true) {
                            val header = input.readLine() ?: return
                            if (header.isEmpty()) break
                        }
                        val served = requests.incrementAndGet()
                        val body = """{"ok":true}"""
                        output.write(
                            (
                                "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n" +
                                    "Content-Length: ${body.toByteArray().size}\r\n\r\n$body"
                                ).toByteArray(),
                        )
                        output.flush()
                        if (served >= closeAfterResponses) return
                    }
                }
            } catch (ignored: Throwable) {
                /* 服务端这侧抛什么都无所谓:客户端已经拿到它要的那次失败。 */
            }
        }

        override fun close() {
            runCatching { server.close() }
        }
    }

    /** 成功就返回 body,失败就返回异常 —— 两条路都要能被判据看见。 */
    private fun call(client: NativeHttpClient, url: String, timeoutMs: Long = 5_000): Pair<JSONObject?, Throwable?> =
        runBlocking {
            try {
                client.request(
                    UUID.randomUUID().toString(), UUID.randomUUID().toString(),
                    JSONObject().put("url", url).put("method", "GET").put("timeoutMs", timeoutMs),
                ) { _, _ -> } to null
            } catch (error: Throwable) {
                null to error
            }
        }

    /* 观测值只能走 logcat:Instrumentation 的 println 不进 JUnit 报告,
       而"死连接那一次到底拿到成功还是可重试错误"是要写进报告的读数。 */
    private fun note(text: String) {
        android.util.Log.i("HaminnHttpProbe", text)
    }

    @Test
    fun sequentialRequestsReuseOneConnection() {
        val probe = Probe()
        try {
            val client = NativeHttpClient(FileStore(context))
            repeat(3) { index ->
                val (body, error) = call(client, probe.url)
                assertTrue(
                    "第 ${index + 1} 次请求就失败了:$error —— 这条用例要证的是「三次请求一条连接」," +
                        "请求本身先得成",
                    error == null && body != null && body.optInt("status") == 200,
                )
            }
            assertEquals(
                "服务端应当只接受 1 条连接(连接池命中),实际接受了 ${probe.accepts.get()} 条 —— " +
                    "池子没复用:检查 client() 是不是还在 new OkHttpClient,或者钉定 DNS 是不是" +
                    "又变回了匿名 lambda(相等性=身份,每个请求算出的 Address 都不同)",
                1L, probe.accepts.get().toLong(),
            )
            assertEquals("服务端应当收到 3 次请求", 3L, probe.requests.get().toLong())
            note("reuse: accepts=${probe.accepts.get()} requests=${probe.requests.get()}")
        } finally {
            probe.close()
        }
    }

    @Test
    fun pooledConnectionIsNotHandedOutAfterAIdleGap() {
        /* 闲置上限那条常量(3 秒)必须被真的读一次,否则 3 秒和 300 秒在测试里
           长得一模一样。判据是「间隔超过上限的下一次请求**不许**复用」——
           它同时也是"没有回退"的证据:间隔久了就退化成新建连接,和改之前一样。
           4 秒 > 3 秒上限,接缝留 1 秒给清理任务。 */
        val probe = Probe()
        try {
            val client = NativeHttpClient(FileStore(context))
            val (firstBody, firstError) = call(client, probe.url)
            assertTrue("第一次请求就该成功,实际 $firstError", firstError == null && firstBody != null)
            assertEquals("第一次请求之后应当只有 1 条连接", 1L, probe.accepts.get().toLong())

            Thread.sleep(4_000)

            val (secondBody, secondError) = call(client, probe.url)
            assertTrue("闲置之后的那次请求就该成功,实际 $secondError", secondError == null && secondBody != null)
            assertEquals(
                "闲置 4 秒(超过 3 秒上限)之后不许再递出池子里那条:服务端可能早就把它关了," +
                    "递出去就是一次页面侧看得见的失败。实际接受了 ${probe.accepts.get()} 条连接",
                2L, probe.accepts.get().toLong(),
            )
            note("idle-gap: accepts=${probe.accepts.get()} requests=${probe.requests.get()}")
        } finally {
            probe.close()
        }
    }

    @Test
    fun stalePooledConnectionLeavesAPageRetryableOutcome() {
        /* 服务端响应完第一条就把连接关掉。客户端池子里那条连接已经死了,但 OkHttp 的
           isHealthy 看不出来(对端 FIN 之后本地 socket 并没有 isClosed/isShutdown),
           所以第二次请求会拿它去发 —— 正好走到「复用才会有的」那个边角上。 */
        val probe = Probe(closeAfterResponses = 1)
        try {
            val client = NativeHttpClient(FileStore(context))
            val (firstBody, firstError) = call(client, probe.url)
            assertTrue("第一次请求就该成功(它是建连的那一次),实际 $firstError", firstError == null && firstBody != null)
            assertEquals("第一次请求之前应当只有 1 条连接", 1L, probe.accepts.get().toLong())

            /* 给服务端一点时间把 FIN 发出来,再让客户端拿那条死连接去发。 */
            Thread.sleep(500)
            val startedAt = System.currentTimeMillis()
            val (secondBody, secondError) = call(client, probe.url)
            val elapsed = System.currentTimeMillis() - startedAt

            assertTrue(
                "复用带来的这个边角不许挂到读超时以外:实测 ${elapsed}ms(读超时设的是 5000ms)",
                elapsed < 5_000,
            )
            assertTrue(
                "死连接上的那次请求,页面侧要么拿到成功、要么拿到一个**能重试**的网络错误;" +
                    "实际是 body=$secondBody error=$secondError" +
                    "(不可重试的错误会让页面整次判死 —— 2026-09-30 的「内部错误」就是这么来的)",
                secondBody != null || secondError is HaminnException,
            )
            if (secondError != null) {
                val failure = secondError as HaminnException
                assertEquals("死连接上的失败要报成网络错误", ErrorCodes.NETWORK, failure.code)
                assertTrue("死连接上的失败必须标成可重试,否则页面只能整次判死", failure.retryable)
            }
            note(
                "stale: outcome=${if (secondBody != null) "success" else "retryable-network-error"} " +
                    "accepts=${probe.accepts.get()} requests=${probe.requests.get()} elapsed=${elapsed}ms " +
                    "detail=$secondError",
            )
        } finally {
            probe.close()
        }
    }
}
