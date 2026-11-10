package io.github.daddymean.agentickeyboard.vscreen

/**
 * Isolated companion-app bridge to an installed Agent-VScreen on this phone.
 *
 * The HTTP surface is localhost only. The access token is sent only as an
 * `Authorization: Bearer` header — never as a query parameter, never in the
 * path, and never in the JSON body. Agent-VScreen also accepts
 * `X-VScreen-Token` and `?token=`; this client uses neither.
 *
 * Default-off: [VscreenCompanion.plan] refuses unless [VscreenAction.enabled]
 * is true. Security-sensitive; needs Keith's sign-off before it is turned on.
 */
object VscreenEndpoint {
    const val DEFAULT_HOST = "127.0.0.1"
    const val DEFAULT_PORT = 18765
    const val RUN_PATH = "/v1/run"
    const val AUTHORIZATION = "Authorization"

    private val LOOPBACK = setOf("127.0.0.1", "localhost", "::1", "[::1]")

    fun isLoopbackHost(host: String): Boolean = host.trim().lowercase() in LOOPBACK

    fun isValidPort(port: Int): Boolean = port in 1..65535

    /**
     * `http://127.0.0.1:18765/v1/run` with no query string. Null if the host
     * is not loopback or the port is out of range.
     */
    fun runUrl(host: String = DEFAULT_HOST, port: Int = DEFAULT_PORT): String? {
        val h = host.trim()
        if (!isLoopbackHost(h) || !isValidPort(port)) return null
        val authority = if (h == "::1") "[::1]" else h
        return "http://$authority:$port$RUN_PATH"
    }

    /** `Authorization` header value. Empty token is rejected by [plan]. */
    fun authorizationHeaderValue(token: String): String = "Bearer $token"

    fun headers(token: String): Map<String, String> =
        mapOf(AUTHORIZATION to authorizationHeaderValue(token))

    /**
     * JSON body for `POST /v1/run`. Only the command args — the token must
     * not appear here.
     */
    fun runBody(args: List<String>): String = buildString {
        append("{\"args\":[")
        args.forEachIndexed { i, arg ->
            if (i > 0) append(',')
            append('"')
            append(escapeJson(arg))
            append('"')
        }
        append("]}")
    }

    fun urlContainsToken(url: String, token: String): Boolean {
        if (token.isEmpty()) return false
        return url.contains(token)
    }

    private fun escapeJson(s: String): String = buildString(s.length) {
        for (c in s) when (c) {
            '\\' -> append("\\\\")
            '"' -> append("\\\"")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> append(c)
        }
    }
}

data class VscreenHttpRequest(
    val method: String,
    val url: String,
    val headers: Map<String, String>,
    val body: String,
)
