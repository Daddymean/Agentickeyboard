package dev.context.app.sync

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Status and body of one sync POST. */
internal data class HttpResponse(val code: Int, val body: String)

/** Sends one JSON body to the sync endpoint; injectable so tests stay offline. */
internal fun interface SyncTransport {
  @Throws(IOException::class)
  fun post(url: String, token: String, body: String): HttpResponse
}

/**
 * The server answered with a non-2xx status. Cursors are not advanced; the
 * worker retries. [body] is the server's (truncated) error text and never
 * contains the token.
 */
class SyncHttpException(val code: Int, val body: String) :
  IOException("sync failed: HTTP $code${if (body.isBlank()) "" else ": " + body.take(200)}")

/** Plain [HttpURLConnection] transport: no extra dependencies, no keep-alive tuning. */
internal object HttpUrlConnectionTransport : SyncTransport {
  private const val CONNECT_TIMEOUT_MS = 15_000
  private const val READ_TIMEOUT_MS = 30_000
  private const val MAX_ERROR_CHARS = 2_000

  override fun post(url: String, token: String, body: String): HttpResponse {
    val bytes = body.toByteArray(Charsets.UTF_8)
    val conn = URL(url).openConnection() as HttpURLConnection
    try {
      conn.requestMethod = "POST"
      conn.connectTimeout = CONNECT_TIMEOUT_MS
      conn.readTimeout = READ_TIMEOUT_MS
      conn.useCaches = false
      conn.doOutput = true
      conn.setRequestProperty("Authorization", "Bearer $token")
      conn.setRequestProperty("Content-Type", "application/json")
      conn.setFixedLengthStreamingMode(bytes.size)
      conn.outputStream.use { it.write(bytes) }
      val code = conn.responseCode
      val stream = if (code in 200..299) conn.inputStream else conn.errorStream
      val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
      return HttpResponse(code, if (code in 200..299) text else text.take(MAX_ERROR_CHARS))
    } finally {
      conn.disconnect()
    }
  }
}
