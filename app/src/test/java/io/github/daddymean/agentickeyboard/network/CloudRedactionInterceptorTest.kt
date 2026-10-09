package io.github.daddymean.agentickeyboard.network

import com.squareup.moshi.Moshi
import okhttp3.Call
import okhttp3.Connection
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import java.util.concurrent.TimeUnit

/**
 * Runs real Gemini request bodies - serialized exactly as Retrofit's Moshi converter
 * does in production - through [CloudRedactionInterceptor] and checks that what
 * would go on the wire is both redacted and still valid JSON.
 */
class CloudRedactionInterceptorTest {

    private val moshi = Moshi.Builder().build()
    private val requestAdapter = moshi.adapter(GenerateContentRequest::class.java)
    private val retrofit = Retrofit.Builder()
        .baseUrl("https://generativelanguage.googleapis.com/")
        .addConverterFactory(MoshiConverterFactory.create(moshi))
        .build()

    @After
    fun restorePolicy() {
        CloudPrivacyPolicy.redactionEnabled = true
    }

    @Test
    fun redactsCardNumberAtStartOfLine() {
        assertRedactsTo("Card:\n4111 1111 1111 1111", "Card:\n[REDACTED_FINANCIAL]")
    }

    @Test
    fun redactsSsnIpUrlAndEmailAtStartOfLine() {
        assertRedactsTo("SSN\n123-45-6789", "SSN\n[REDACTED_SSN]")
        assertRedactsTo("ip\n10.0.0.1", "ip\n[REDACTED_IP]")
        assertRedactsTo("Line1\nhttps://a.b/c\nLine2", "Line1\n[REDACTED_URL]\nLine2")
        assertRedactsTo("Hi\nbob@example.com", "Hi\n[REDACTED_EMAIL]")
    }

    @Test
    fun redactsQuotedPasswordAndKeepsJsonValid() {
        val sent = assertRedactsTo("my password: \"hunter2\" ok", "my password=[REDACTED_SECRET] ok")
        assertFalse(sent.contains("hunter2"))
    }

    @Test
    fun redactsSingleQuotedAndSpacedSecretsWithoutStrayQuotesAndKeepsJsonValid() {
        assertRedactsTo("api_key = 'x'", "api_key=[REDACTED_SECRET]")
        val sent = assertRedactsTo(
            "line one\nsecret: \"two words\", then \"quoted\" text",
            "line one\nsecret=[REDACTED_SECRET], then \"quoted\" text"
        )
        assertFalse(sent.contains("two words"))
    }

    @Test
    fun redactsQuotedUrlAndKeepsJsonValid() {
        assertRedactsTo("Read \"https://example.com/a\" first", "Read \"[REDACTED_URL]\" first")
    }

    @Test
    fun redactsEveryStringValueAndPreservesTheRestOfTheRequest() {
        val request = GenerateContentRequest(
            contents = listOf(
                Content(listOf(Part("Email\njane@example.com"), Part("Plain words, nothing to hide."))),
                Content(listOf(Part("Call (555) 867-5309\tor 192.168.1.42")))
            ),
            generationConfig = GenerationConfig(responseMimeType = "application/json", temperature = 0.2f),
            systemInstruction = Content(listOf(Part("You are a helpful \"keyboard\" assistant.\nBe brief.")))
        )

        val sent = interceptAndCapture(productionBody(request))
        val parsed = parseStrict(sent)

        assertEquals("Email\n[REDACTED_EMAIL]", parsed.contents[0].parts[0].text)
        assertEquals("Plain words, nothing to hide.", parsed.contents[0].parts[1].text)
        assertEquals("Call [REDACTED_PHONE]\tor [REDACTED_IP]", parsed.contents[1].parts[0].text)
        assertEquals(request.generationConfig, parsed.generationConfig)
        assertEquals(request.systemInstruction, parsed.systemInstruction)
        assertFalse(sent.contains("jane@example.com"))
        assertFalse(sent.contains("867-5309"))
    }

    @Test
    fun preservesNonAsciiTextWhileRedacting() {
        assertRedactsTo(
            "Café 🎉 Nº 42 — reach me at\nbob@example.com ✓",
            "Café 🎉 Nº 42 — reach me at\n[REDACTED_EMAIL] ✓"
        )
    }

    @Test
    fun ordinaryRequestIsSentUnchanged() {
        val body = productionBody(requestWithText("Please meet me at 4:30 tomorrow.\nBring 12 \"blue\" folders."))
        val original = Request.Builder().url(URL).post(body).build()
        val chain = CapturingChain(original)

        CloudRedactionInterceptor().intercept(chain)

        assertSame(original, chain.proceeded)
    }

    @Test
    fun redactionDisabledSendsOriginalBody() {
        CloudPrivacyPolicy.redactionEnabled = false
        val body = productionBody(requestWithText("Hi\nbob@example.com"))
        val original = Request.Builder().url(URL).post(body).build()
        val chain = CapturingChain(original)

        CloudRedactionInterceptor().intercept(chain)

        assertSame(original, chain.proceeded)
    }

    @Test
    fun redactedBodyKeepsJsonContentType() {
        val body = productionBody(requestWithText("Card:\n4111 1111 1111 1111"))
        val chain = CapturingChain(Request.Builder().url(URL).post(body).build())

        CloudRedactionInterceptor().intercept(chain)

        assertEquals(body.contentType(), chain.proceeded!!.body!!.contentType())
    }

    @Test
    fun nonJsonBodyStillGetsTextRedaction() {
        val body = "Hi\nbob@example.com, card 4111 1111 1111 1111".toRequestBody("text/plain".toMediaType())

        val sent = interceptAndCapture(body)

        assertEquals("Hi\n[REDACTED_EMAIL], card [REDACTED_FINANCIAL]", sent)
    }

    @Test
    fun malformedJsonBodyFailsClosedToTextRedaction() {
        val truncated = """{"contents":[{"parts":[{"text":"write to bob@example.com about 123-45-6789"""
        val body = truncated.toRequestBody(JSON)

        val sent = interceptAndCapture(body)

        assertFalse(sent.contains("bob@example.com"))
        assertFalse(sent.contains("123-45-6789"))
        assertTrue(sent.contains("[REDACTED_EMAIL]"))
        assertTrue(sent.contains("[REDACTED_SSN]"))
    }

    @Test
    fun redactorCopiesNumbersBooleansAndNullsThrough() {
        val json = """{"a":"ssn 123-45-6789","n":[0,-1,0.25,1.5E10,12345678901],"b":true,"z":null,"o":{}}"""

        val result = CloudRequestRedactor.redactJsonStringValues(json)

        assertNotNull(result)
        assertEquals(
            """{"a":"ssn [REDACTED_SSN]","n":[0,-1,0.25,1.5E+10,12345678901],"b":true,"z":null,"o":{}}""",
            result!!.text
        )
        assertEquals(1, result.replacements)
    }

    @Test
    fun redactorRejectsTrailingGarbage() {
        assertEquals(null, CloudRequestRedactor.redactJsonStringValues("""{"a":"x"} {"b":"y"}"""))
        assertEquals(null, CloudRequestRedactor.redactJsonStringValues("not json at all"))
    }

    // --- helpers ---------------------------------------------------------------

    /** Sends [draft] as a production request and asserts the wire text decodes to [expected]. */
    private fun assertRedactsTo(draft: String, expected: String): String {
        val sent = interceptAndCapture(productionBody(requestWithText(draft)))
        val parsed = parseStrict(sent)
        assertEquals(expected, parsed.contents.single().parts.single().text)
        return sent
    }

    private fun requestWithText(text: String) =
        GenerateContentRequest(contents = listOf(Content(listOf(Part(text)))))

    /** Serializes [request] the same way Retrofit does for `@Body` in [GeminiApiService]. */
    @Suppress("UNCHECKED_CAST")
    private fun productionBody(request: GenerateContentRequest): RequestBody {
        val converter = MoshiConverterFactory.create(moshi).requestBodyConverter(
            GenerateContentRequest::class.java, emptyArray(), emptyArray(), retrofit
        ) as retrofit2.Converter<GenerateContentRequest, RequestBody>
        return converter.convert(request)!!
    }

    private fun interceptAndCapture(body: RequestBody): String {
        val chain = CapturingChain(Request.Builder().url(URL).post(body).build())
        CloudRedactionInterceptor().intercept(chain)
        val buffer = Buffer()
        chain.proceeded!!.body!!.writeTo(buffer)
        return buffer.readUtf8()
    }

    /** Strict parse: throws if [json] is not a single valid request document. */
    private fun parseStrict(json: String): GenerateContentRequest {
        val parsed = requestAdapter.failOnUnknown().fromJson(json)
        assertNotNull("request body did not parse", parsed)
        // Round trip: re-serializing the parsed request reproduces the wire body.
        assertEquals(json, requestAdapter.toJson(parsed))
        return parsed!!
    }

    private class CapturingChain(private val request: Request) : Interceptor.Chain {
        var proceeded: Request? = null

        override fun request(): Request = request

        override fun proceed(request: Request): Response {
            proceeded = request
            return Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body("{}".toResponseBody(JSON))
                .build()
        }

        override fun connection(): Connection? = null
        override fun call(): Call = throw UnsupportedOperationException()
        override fun connectTimeoutMillis(): Int = 0
        override fun withConnectTimeout(timeout: Int, unit: TimeUnit): Interceptor.Chain = this
        override fun readTimeoutMillis(): Int = 0
        override fun withReadTimeout(timeout: Int, unit: TimeUnit): Interceptor.Chain = this
        override fun writeTimeoutMillis(): Int = 0
        override fun withWriteTimeout(timeout: Int, unit: TimeUnit): Interceptor.Chain = this
    }

    private companion object {
        const val URL = "https://generativelanguage.googleapis.com/v1beta/models/test:generateContent"
        val JSON = "application/json; charset=UTF-8".toMediaType()
    }
}
