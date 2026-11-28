package io.github.daddymean.agentickeyboard.models

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.net.InetSocketAddress
import java.security.MessageDigest
import java.util.Collections

/** The real OkHttp fetcher against a local JDK HTTP server that speaks (or refuses) Range. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class OkHttpModelFetcherTest {
    private val data = ByteArray(700_000) { (it * 17 + 3).toByte() }
    private val pin = MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }
    private lateinit var server: HttpServer
    private lateinit var root: File
    private lateinit var context: Context
    private val rangeHeaders: MutableList<String?> = Collections.synchronizedList(mutableListOf())
    private val acceptEncodings: MutableList<String?> = Collections.synchronizedList(mutableListOf())
    @Volatile private var mode = Mode.RANGE_OK
    @Volatile private var dropAfter = -1
    @Volatile private var dropsLeft = 0

    private enum class Mode { RANGE_OK, IGNORE_RANGE, WRONG_RANGE, REJECT_416, ERROR_404 }

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        root = File(context.noBackupFilesDir, "okhttp-fetch-test").apply { deleteRecursively(); mkdirs() }
        context.getSharedPreferences(ModelConsent.PREFS, Context.MODE_PRIVATE).edit().clear().commit()
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/model.bin") { ex -> handle(ex) }
        server.start()
    }

    @After
    fun tearDown() = server.stop(0)

    private fun url() = "http://127.0.0.1:${server.address.port}/model.bin"

    private fun handle(ex: HttpExchange) {
        rangeHeaders += ex.requestHeaders.getFirst("Range")
        acceptEncodings += ex.requestHeaders.getFirst("Accept-Encoding")
        val range = ex.requestHeaders.getFirst("Range")?.let { Regex("bytes=(\\d+)-").find(it)?.groupValues?.get(1)?.toInt() }
        try {
            when {
                mode == Mode.ERROR_404 -> { ex.sendResponseHeaders(404, -1) }
                range != null && mode == Mode.REJECT_416 -> { ex.sendResponseHeaders(416, -1) }
                range != null && mode != Mode.IGNORE_RANGE -> {
                    val start = if (mode == Mode.WRONG_RANGE) 0 else range
                    val body = data.copyOfRange(start, data.size)
                    ex.responseHeaders.add("Content-Range", "bytes $start-${data.size - 1}/${data.size}")
                    ex.sendResponseHeaders(206, body.size.toLong())
                    writeMaybeDropping(ex, body)
                }
                else -> {
                    ex.sendResponseHeaders(200, data.size.toLong())
                    writeMaybeDropping(ex, data)
                }
            }
        } finally {
            ex.close()
        }
    }

    private fun writeMaybeDropping(ex: HttpExchange, body: ByteArray) {
        val out = ex.responseBody
        if (dropsLeft > 0 && dropAfter in 0 until body.size) {
            dropsLeft--
            out.write(body, 0, dropAfter)
            out.flush()
            ex.close() // fewer bytes than Content-Length: the client sees a truncated body
        } else {
            out.write(body)
        }
    }

    private fun spec() = ModelSpec("okhttp-model", "model.bin", url(), pin, data.size.toLong(), "Apache-2.0")

    private fun manager() = ModelManager(root, ModelConsent(context), OkHttpModelFetcher(), { Long.MAX_VALUE }, { }, listOf(0L, 0L, 0L, 0L))
        .also { it.setOptedIn(spec(), true) }

    @Test
    fun aTruncatedBodyIsResumedWithARangeRequestAndEndsVerified() {
        dropAfter = 250_000
        dropsLeft = 1
        val result = manager().download(spec())
        assertTrue("result=$result", result is DownloadResult.Ready)
        assertEquals(listOf(null, "bytes=250000-"), rangeHeaders.toList())
        assertTrue(acceptEncodings.all { it == "identity" })
    }

    @Test
    fun severalDropsInARowStillFinishWhenEachOneMakesProgress() {
        dropAfter = 100_000
        dropsLeft = 3
        val result = manager().download(spec())
        assertTrue("result=$result", result is DownloadResult.Ready)
        assertEquals(listOf(null, "bytes=100000-", "bytes=200000-", "bytes=300000-"), rangeHeaders.toList())
    }

    @Test
    fun aServerThatIgnoresRangeIsHandledByRewritingFromTheStart() {
        File(File(root, "okhttp-model").apply { mkdirs() }, "model.bin.part").writeBytes(data.copyOf(1_000))
        File(root, "okhttp-model/model.bin.part.meta").writeText("${url()}\n$pin\n${data.size}")
        mode = Mode.IGNORE_RANGE
        val result = manager().download(spec())
        assertTrue("result=$result", result is DownloadResult.Ready)
        assertEquals(listOf("bytes=1000-"), rangeHeaders.toList())
    }

    @Test
    fun aWrongContentRangeIsRejectedAndTheDownloadRestartsClean() {
        File(File(root, "okhttp-model").apply { mkdirs() }, "model.bin.part").writeBytes(data.copyOf(1_000))
        File(root, "okhttp-model/model.bin.part.meta").writeText("${url()}\n$pin\n${data.size}")
        mode = Mode.WRONG_RANGE
        val result = manager().download(spec())
        assertTrue("result=$result", result is DownloadResult.Ready)
        assertEquals(listOf("bytes=1000-", null), rangeHeaders.toList())
    }

    @Test
    fun a416RestartsFromZero() {
        File(File(root, "okhttp-model").apply { mkdirs() }, "model.bin.part").writeBytes(data.copyOf(1_000))
        File(root, "okhttp-model/model.bin.part.meta").writeText("${url()}\n$pin\n${data.size}")
        mode = Mode.REJECT_416
        val result = manager().download(spec())
        assertTrue("result=$result", result is DownloadResult.Ready)
        assertEquals(listOf("bytes=1000-", null), rangeHeaders.toList())
    }

    @Test
    fun a404FailsWithoutRetrying() {
        mode = Mode.ERROR_404
        val result = manager().download(spec())
        assertTrue(result is DownloadResult.Failed)
        assertEquals(1, rangeHeaders.size)
        assertEquals("download failed: HTTP 404", (result as DownloadResult.Failed).message)
    }

    @Test
    fun contentRangeParsing() {
        assertEquals(100L, OkHttpModelFetcher.contentRangeStart("bytes 100-199/200"))
        assertEquals(0L, OkHttpModelFetcher.contentRangeStart("bytes 0-99/*"))
        assertEquals(-1L, OkHttpModelFetcher.contentRangeStart(null))
        assertEquals(-1L, OkHttpModelFetcher.contentRangeStart("bytes */200"))
        assertEquals(-1L, OkHttpModelFetcher.contentRangeStart("garbage"))
    }
}
