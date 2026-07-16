package io.github.daddymean.agentickeyboard.network

import io.github.daddymean.agentickeyboard.util.CloudTextSanitizer
import okhttp3.Interceptor
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.Buffer

/**
 * Process-wide switch for outbound Gemini request redaction.
 *
 * It defaults on. Keeping the switch outside the interceptor makes a future
 * user-facing Trust Prism toggle possible without rebuilding the Retrofit client.
 */
object CloudPrivacyPolicy {
    @Volatile
    var redactionEnabled: Boolean = true
}

/**
 * Sanitizes the final serialized Gemini request body immediately before OkHttp
 * sends it. Centralizing the guard here protects every AI action, including new
 * endpoints added later, without relying on each caller to remember redaction.
 */
class CloudRedactionInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val originalBody = request.body

        if (!CloudPrivacyPolicy.redactionEnabled || originalBody == null) {
            return chain.proceed(request)
        }

        val buffer = Buffer()
        originalBody.writeTo(buffer)
        val serializedBody = buffer.readUtf8()
        val redacted = CloudTextSanitizer.sanitize(serializedBody)

        if (!redacted.changed) {
            return chain.proceed(request)
        }

        val replacementBody = redacted.text.toRequestBody(originalBody.contentType())
        val redactedRequest = request.newBuilder()
            .method(request.method, replacementBody)
            .build()

        return chain.proceed(redactedRequest)
    }
}
