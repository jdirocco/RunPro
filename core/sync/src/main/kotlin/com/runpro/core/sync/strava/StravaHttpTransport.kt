package com.runpro.core.sync.strava

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.util.UUID

data class StravaHttpResponse(val statusCode: Int, val body: String)

class MultipartFilePart(
    val fieldName: String,
    val fileName: String,
    val contentType: String,
    val bytes: ByteArray
)

/**
 * Injectable HTTP transport seam so `:core:sync` tests run without live network calls while
 * production code uses standard JVM/Android [HttpURLConnection].
 */
interface StravaHttpTransport {
    @Throws(IOException::class)
    fun postForm(url: String, formFields: Map<String, String>): StravaHttpResponse

    @Throws(IOException::class)
    fun postMultipart(
        url: String,
        headers: Map<String, String>,
        fields: Map<String, String>,
        filePart: MultipartFilePart
    ): StravaHttpResponse

    @Throws(IOException::class)
    fun get(url: String, headers: Map<String, String>): StravaHttpResponse
}

/**
 * Standard-library [HttpURLConnection] transport compatible with both JVM 21 and Android API 30+.
 */
class HttpUrlConnectionStravaTransport(
    private val connectTimeoutMillis: Int = DEFAULT_CONNECT_TIMEOUT_MS,
    private val readTimeoutMillis: Int = DEFAULT_READ_TIMEOUT_MS
) : StravaHttpTransport {
    override fun postForm(url: String, formFields: Map<String, String>): StravaHttpResponse {
        val encoded = formFields.entries.joinToString("&") { (k, v) ->
            "${URLEncoder.encode(k, Charsets.UTF_8)}=${URLEncoder.encode(v, Charsets.UTF_8)}"
        }.toByteArray(Charsets.UTF_8)

        return execute(
            url = url,
            method = "POST",
            headers = mapOf("Content-Type" to "application/x-www-form-urlencoded"),
            body = encoded
        )
    }

    override fun postMultipart(
        url: String,
        headers: Map<String, String>,
        fields: Map<String, String>,
        filePart: MultipartFilePart
    ): StravaHttpResponse {
        val boundary = "RunProBoundary${UUID.randomUUID().toString().replace("-", "")}"
        val payload = buildMultipartBody(boundary, fields, filePart)
        val mergedHeaders = headers + ("Content-Type" to "multipart/form-data; boundary=$boundary")
        return execute(url = url, method = "POST", headers = mergedHeaders, body = payload)
    }

    override fun get(url: String, headers: Map<String, String>): StravaHttpResponse =
        execute(url = url, method = "GET", headers = headers, body = null)

    private fun execute(
        url: String,
        method: String,
        headers: Map<String, String>,
        body: ByteArray?
    ): StravaHttpResponse {
        val connection = URI(url).toURL().openConnection() as HttpURLConnection
        return try {
            connection.requestMethod = method
            connection.connectTimeout = connectTimeoutMillis
            connection.readTimeout = readTimeoutMillis
            connection.useCaches = false
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("Accept", "application/json")
            headers.forEach { (key, value) -> connection.setRequestProperty(key, value) }

            if (body != null) {
                connection.doOutput = true
                connection.setFixedLengthStreamingMode(body.size)
                connection.outputStream.use { it.write(body) }
            }

            val status = connection.responseCode
            val stream = if (status in HTTP_OK_START..HTTP_OK_END) {
                connection.inputStream
            } else {
                connection.errorStream
            }
            val responseBody = stream?.use(::readBoundedUtf8).orEmpty()
            StravaHttpResponse(statusCode = status, body = responseBody)
        } finally {
            connection.disconnect()
        }
    }

    private fun buildMultipartBody(
        boundary: String,
        fields: Map<String, String>,
        filePart: MultipartFilePart
    ): ByteArray {
        val out = ByteArrayOutputStream(filePart.bytes.size + MULTIPART_OVERHEAD_BYTES)
        fun writeAscii(text: String) = out.write(text.toByteArray(Charsets.UTF_8))

        fields.forEach { (name, value) ->
            writeAscii("--$boundary\r\n")
            writeAscii(
                "Content-Disposition: form-data; name=\"${sanitizeHeaderValue(name)}\"\r\n\r\n"
            )
            writeAscii(value)
            writeAscii("\r\n")
        }

        val safeField = sanitizeHeaderValue(filePart.fieldName)
        val safeFile = sanitizeHeaderValue(filePart.fileName)
        val safeType = sanitizeHeaderValue(filePart.contentType)
        writeAscii("--$boundary\r\n")
        writeAscii(
            "Content-Disposition: form-data; name=\"$safeField\"; filename=\"$safeFile\"\r\n"
        )
        writeAscii("Content-Type: $safeType\r\n\r\n")
        out.write(filePart.bytes)
        writeAscii("\r\n--$boundary--\r\n")
        return out.toByteArray()
    }

    private fun readBoundedUtf8(input: InputStream): String {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(READ_BUFFER_BYTES)
        var total = 0
        while (total < MAX_RESPONSE_BYTES) {
            val remaining = MAX_RESPONSE_BYTES - total
            val read = input.read(buffer, 0, minOf(buffer.size, remaining))
            if (read < 0) break
            out.write(buffer, 0, read)
            total += read
        }
        return out.toString(Charsets.UTF_8)
    }

    private fun sanitizeHeaderValue(value: String): String =
        value.replace("\r", "").replace("\n", "").replace("\"", "_")

    private companion object {
        const val DEFAULT_CONNECT_TIMEOUT_MS = 15_000
        const val DEFAULT_READ_TIMEOUT_MS = 30_000
        const val HTTP_OK_START = 200
        const val HTTP_OK_END = 299
        const val READ_BUFFER_BYTES = 8 * 1024
        const val MAX_RESPONSE_BYTES = 1024 * 1024
        const val MULTIPART_OVERHEAD_BYTES = 512
    }
}
