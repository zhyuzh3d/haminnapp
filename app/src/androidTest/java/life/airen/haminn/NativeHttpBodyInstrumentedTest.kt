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

/**
 * 读响应体那一段的网络异常必须变成 HaminnException(E_NETWORK, retryable)。
 *
 * 2026-09-30 真机现场:一次生成等了 80 多秒后只报 E_INTERNAL +「内部错误」,而服务端
 * 那张图已经落盘、手机文件库里一个字节都没有。成因是 `execute()` 只包住了
 * `call.execute()`(它只拿到响应头),正文是在 `response.use { … }` 里才从连接上读的,
 * 而那一段没有任何 catch:读超时抛 SocketTimeoutException,连接中途被关抛
 * SocketException —— 两个都不是 HaminnException,裸抛出去被桥按兜底处理成
 * E_INTERNAL,页面侧于是拿到一个**不可重试、也说不清原因**的错误。
 * (按 700ms 的真实节奏压同一个接口,110 次里有 5 次就是这两种。)
 *
 * 三个用例对着一个**只发响应头、不发正文**的本地服务器:
 *  1. 头发完就挂住 ⇒ 读超时;
 *  2. 声明 4096 字节却只给 64 字节再关连接 ⇒ 流提前结束;
 *  3. 反过来:自家异常(响应超过 64 MiB 那条 QUOTA)必须原样透传 ——
 *     这一条是"新 catch 有没有把整个能力上限检查一起吃掉"的反证。
 *
 * 三条的判据都落在**异常的类型与错误码**上,所以修好之前它们会红在断言上,而不是
 * 红在编译或环境上:老代码抛的是裸 SocketTimeoutException/IOException,
 * `error is HaminnException` 直接不成立。
 */
@RunWith(AndroidJUnit4::class)
class NativeHttpBodyInstrumentedTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    /* 探针服务器必须绑在本机的一个**站点本地**地址上:127.0.0.1 会被
       resolveAndValidate 以 ORIGIN_DENIED 挡掉(那是另一条既有规则,
       PersistenceInstrumentedTest.nativeHttpRejectsLoopbackBeforeAuthorization 守着它)。
       关掉 Wi-Fi 时这个地址不存在,用例会带着明确的理由失败,而不是静默跳过。 */
    private fun siteLocalAddress(): String =
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { runCatching { it.isUp }.getOrDefault(false) }
            .filter { runCatching { it.isLoopback }.getOrDefault(true).not() }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .firstOrNull { it.isSiteLocalAddress }
            ?.hostAddress
            ?: error("本机没有站点本地 IPv4 地址(关掉 Wi-Fi 就无从下手),读正文那一段的网络异常在这个状态下验不了")

    /** 起一个只服务一次的服务器:接受连接后按 block 处置,无论如何都收摊。
        工作线程是 daemon:用例 1 与 3 让服务端挂住 20 秒等客户端的读超时先到,
        那 20 秒不该拖住测试进程。 */
    private fun once(block: (Socket) -> Unit): Pair<String, AutoCloseable> {
        val address = siteLocalAddress()
        val server = ServerSocket(0, 4, InetAddress.getByName(address))
        val worker = Thread {
            try {
                server.accept().use { socket -> block(socket) }
            } catch (ignored: Throwable) {
                /* 服务器这侧抛什么都无所谓:客户端已经拿到它要的那次失败。 */
            } finally {
                runCatching { server.close() }
            }
        }
        worker.isDaemon = true
        worker.start()
        return "http://$address:${server.localPort}/probe" to AutoCloseable {
            runCatching { server.close() }
        }
    }

    private fun out(socket: Socket, text: String) {
        socket.getOutputStream().apply {
            write(text.toByteArray())
            flush()
        }
    }

    /* timeoutMs 有下限 5 秒(见 prepare),所以传 5000 就是"最短的那次读超时"。
       不传的话会落到默认的 30 秒,读超时永远先于它到不了 —— 那样这条用例
       就退化成和下面"流被掐断"那条一样了(实测踩过:用例跑了 20.079 秒,
       正好是服务端 sleep 的长度,说明超时根本没触发)。 */
    private fun request(url: String, timeoutMs: Long = 5_000): Any? = runBlocking {
        runCatching {
            NativeHttpClient(FileStore(context)).request(
                UUID.randomUUID().toString(), UUID.randomUUID().toString(),
                JSONObject().put("url", url).put("method", "GET").put("timeoutMs", timeoutMs),
            ) { _, _ -> }
        }.exceptionOrNull()
    }

    private fun assertRetryableNetworkError(error: Any?, what: String) {
        assertTrue(
            "$what 必须变成 HaminnException;实际抛出的是 $error" +
                "(裸抛的话桥会兜底成 E_INTERNAL +「内部错误」,页面侧就整次判死了)",
            error is HaminnException,
        )
        val failure = error as HaminnException
        assertEquals("$what 的错误码", ErrorCodes.NETWORK, failure.code)
        assertTrue("$what 必须标成可重试,否则页面侧仍然只能整次判死", failure.retryable)
    }

    @Test fun bodyReadTimeoutBecomesRetryableNetworkError() {
        /* 头发完就挂住,一个字节正文都不发 —— 客户端的读超时先到。
           timeoutMs 有下限 5 秒(见 prepare),所以这条大约要 5 秒。 */
        val (url, server) = once { socket ->
            out(socket, "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: 4096\r\n\r\n")
            Thread.sleep(20_000)
        }
        try {
            assertRetryableNetworkError(request(url), "读正文超时")
        } finally {
            server.close()
        }
    }

    @Test fun bodyCutShortBecomesRetryableNetworkError() {
        val (url, server) = once { socket ->
            out(socket, "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: 4096\r\n\r\n")
            socket.getOutputStream().apply {
                write(ByteArray(64) { 'x'.code.toByte() })
                flush()
            }
            /* block 返回后 once() 会把连接关掉:声明 4096 却只给了 64,
               客户端读到的是「流提前结束」—— 实测堆栈里那条 SocketException 就是这个。 */
        }
        try {
            assertRetryableNetworkError(request(url), "正文被中途掐断")
        } finally {
            server.close()
        }
    }

    @Test fun ownQuotaErrorPassesThroughUntouched() {
        val (url, server) = once { socket ->
            /* 64 MiB 之上:response 那段自己抛 QUOTA。这条必须在**新 catch 之前**成立,
               否则"响应过大"这类自家判据会被读正文那段的新 catch 吞成网络故障。 */
            out(socket, "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: 73400320\r\n\r\n")
            Thread.sleep(20_000)
        }
        try {
            val error = request(url)
            assertTrue("自家判据必须还是 HaminnException;实际抛出的是 $error", error is HaminnException)
            assertEquals(
                "响应超过 64 MiB 必须原样报 QUOTA,不许被读正文那段的新 catch 改成网络错误",
                ErrorCodes.QUOTA, (error as HaminnException).code,
            )
        } finally {
            server.close()
        }
    }
}
