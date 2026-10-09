package io.github.daddymean.agentickeyboard.network

import com.squareup.moshi.JsonReader
import com.squareup.moshi.JsonWriter
import io.github.daddymean.agentickeyboard.util.CloudRedactionResult
import io.github.daddymean.agentickeyboard.util.CloudTextSanitizer
import okhttp3.Interceptor
import okhttp3.MediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.Buffer
import java.io.IOException
import java.math.BigDecimal

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
 * Sanitizes the outgoing Gemini request body immediately before OkHttp sends it.
 * Centralizing the guard here protects every AI action, including new endpoints
 * added later, without relying on each caller to remember redaction.
 *
 * JSON bodies are redacted value by value (see [CloudRequestRedactor]) so the
 * sanitizer sees the user's real text rather than its JSON-escaped form, and the
 * request stays valid JSON.
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
        val redacted = CloudRequestRedactor.redact(serializedBody, originalBody.contentType())

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

/**
 * Applies [CloudTextSanitizer] to a serialized request body.
 *
 * The sanitizer's patterns are written for plain text. Run over a serialized JSON
 * document they see `\n` instead of a line break and `\"` instead of a quote, so a
 * value at the start of a line slipped through and a quoted secret or URL was cut
 * mid-escape, leaving invalid JSON. JSON bodies are therefore parsed, every string
 * *value* is sanitized as the plain text it decodes to, and the document is written
 * back out. Object keys belong to the request schema and are left alone; numbers,
 * booleans and nulls are copied through.
 *
 * Fails closed: a body that is not JSON, or claims to be JSON but does not parse,
 * gets the previous whole-text sanitization rather than being sent unredacted.
 */
object CloudRequestRedactor {

    fun redact(body: String, contentType: MediaType?): CloudRedactionResult {
        if (isJson(contentType)) {
            redactJsonStringValues(body)?.let { return it }
        }
        return CloudTextSanitizer.sanitize(body)
    }

    /**
     * Sanitizes every string value in [json]. Returns null when [json] is not a
     * single well-formed JSON document, so the caller can fall back. When nothing is
     * redacted the original text is returned untouched.
     */
    fun redactJsonStringValues(json: String): CloudRedactionResult? {
        if (json.isBlank()) return null
        return try {
            val reader = JsonReader.of(Buffer().writeUtf8(json))
            val output = Buffer()
            val writer = JsonWriter.of(output).apply { serializeNulls = true }
            var replacements = 0

            fun copyValue() {
                when (reader.peek()) {
                    JsonReader.Token.BEGIN_OBJECT -> {
                        reader.beginObject()
                        writer.beginObject()
                        while (reader.hasNext()) {
                            writer.name(reader.nextName())
                            copyValue()
                        }
                        reader.endObject()
                        writer.endObject()
                    }
                    JsonReader.Token.BEGIN_ARRAY -> {
                        reader.beginArray()
                        writer.beginArray()
                        while (reader.hasNext()) copyValue()
                        reader.endArray()
                        writer.endArray()
                    }
                    JsonReader.Token.STRING -> {
                        val result = CloudTextSanitizer.sanitize(reader.nextString())
                        replacements += result.replacements
                        writer.value(result.text)
                    }
                    // nextString() on a number returns its literal; BigDecimal keeps it exact.
                    JsonReader.Token.NUMBER -> writer.value(BigDecimal(reader.nextString()))
                    JsonReader.Token.BOOLEAN -> writer.value(reader.nextBoolean())
                    JsonReader.Token.NULL -> {
                        reader.nextNull<Any>()
                        writer.nullValue()
                    }
                    else -> throw IOException("Unexpected JSON token")
                }
            }

            copyValue()
            if (reader.peek() != JsonReader.Token.END_DOCUMENT) return null
            writer.close()

            if (replacements == 0) {
                CloudRedactionResult(json, 0)
            } else {
                CloudRedactionResult(output.readUtf8(), replacements)
            }
        } catch (e: IOException) {
            // JsonEncodingException (malformed JSON) is an IOException.
            null
        } catch (e: RuntimeException) {
            // JsonDataException, NumberFormatException, nesting limits, etc.
            null
        }
    }

    private fun isJson(contentType: MediaType?): Boolean {
        if (contentType == null) return false
        val subtype = contentType.subtype.lowercase()
        return subtype == "json" || subtype.endsWith("+json")
    }
}
