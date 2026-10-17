package com.textserv.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit

@Serializable
data class LogBody(
    val direction: String,
    val addresses: List<String>,
    val sender: String? = null,
    val body: String,
    val ts: Long,
    val status: String? = null,
    @SerialName("phone_ref") val phoneRef: String,
    val attachments: List<String> = emptyList(),
)

@Serializable
data class AttachmentInfo(
    val id: String,
    @SerialName("content_type") val contentType: String,
    val filename: String,
    val size: Long = 0,
)

@Serializable
data class ServerMessage(
    val id: String,
    val direction: String,
    val address: String,
    val participants: List<String> = emptyList(),
    val body: String,
    val ts: Long,
    val status: String,
    val attachments: List<AttachmentInfo> = emptyList(),
)

@Serializable
private data class RegisterBody(val endpoint: String, val p256dh: String?, val auth: String?)

@Serializable
private data class StatusBody(val status: String, @SerialName("phone_ref") val phoneRef: String? = null)

class HttpException(val code: Int, message: String) : IOException("HTTP $code: $message") {
    /** Worth retrying later (server error, auth not yet fixed, etc.) vs. permanently rejected. */
    val retryable get() = code >= 500 || code == 401 || code == 408 || code == 429
}

/** Client for the textserv server in ../server. */
class ServerApi(
    private val baseUrl: String,
    private val token: String,
    private val deviceId: String,
    deviceName: String,
    deviceNumbers: Collection<String>,
) {
    // Header values must be printable ASCII.
    private val deviceName = deviceName.filter { it.code in 0x20..0x7E }.trim().ifEmpty { "Android phone" }.take(64)
    private val deviceNumbers = deviceNumbers.joinToString(",") { n -> n.filter { it.code in 0x21..0x7E && it != ',' } }

    companion object {
        val json = Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = true }
        private val JSON = "application/json".toMediaType()
        private val client = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(120, TimeUnit.SECONDS)
            .build()

        /** `null` until there's a server and a number to identify this phone by. */
        fun fromPrefs(p: Prefs): ServerApi? {
            val numbers = p.deviceNumbers
            if (!p.serverConfigured || numbers.isEmpty()) return null
            return ServerApi(p.serverUrl, p.token, p.deviceId, p.deviceName, numbers)
        }
    }

    private fun req(path: String) = Request.Builder().url(baseUrl + path).apply {
        header("Authorization", "Bearer $token")
        header("X-Device-Id", deviceId)
        header("X-Device-Name", deviceName)
        header("X-Device-Numbers", deviceNumbers)
    }

    private suspend fun <T> call(r: Request, read: (Response) -> T): T = withContext(Dispatchers.IO) {
        client.newCall(r).execute().use { resp ->
            if (!resp.isSuccessful) throw HttpException(resp.code, resp.body.string().take(200))
            read(resp)
        }
    }

    private fun jsonBody(s: String): RequestBody = s.toRequestBody(JSON)

    suspend fun ping() = call(req("/api/threads").build()) { }

    suspend fun register(endpoint: String, p256dh: String?, auth: String?) = call(
        req("/api/register").post(jsonBody(json.encodeToString(RegisterBody(endpoint, p256dh, auth)))).build()
    ) { }

    suspend fun log(body: LogBody): ServerMessage = call(
        req("/api/log").post(jsonBody(json.encodeToString(body))).build()
    ) { json.decodeFromString<ServerMessage>(it.body.string()) }

    suspend fun logBatch(bodies: List<LogBody>) = call(
        req("/api/log/batch").post(jsonBody(json.encodeToString(bodies))).build()
    ) { }

    suspend fun knownRefs(refs: List<String>): Set<String> = call(
        req("/api/known_refs").post(jsonBody(json.encodeToString(refs))).build()
    ) { json.decodeFromString<List<String>>(it.body.string()).toSet() }

    suspend fun uploadAttachment(bytes: ByteArray, contentType: String, filename: String): AttachmentInfo = call(
        req("/api/attachments")
            .header("x-filename", filename.filter { it.code in 0x20..0x7E })
            .post(bytes.toRequestBody(contentType.toMediaType()))
            .build()
    ) { json.decodeFromString<AttachmentInfo>(it.body.string()) }

    suspend fun downloadAttachment(id: String): ByteArray =
        call(req("/api/attachments/$id").build()) { it.body.bytes() }

    suspend fun outbox(): List<ServerMessage> =
        call(req("/api/outbox").build()) { json.decodeFromString<List<ServerMessage>>(it.body.string()) }

    /** Returns false if the server says someone else already claimed/finished it (409) or it's gone (404). */
    suspend fun setStatus(id: String, status: String, phoneRef: String? = null): Boolean = try {
        call(req("/api/messages/$id/status").post(jsonBody(json.encodeToString(StatusBody(status, phoneRef)))).build()) { }
        true
    } catch (e: HttpException) {
        if (e.code == 409 || e.code == 404) false else throw e
    }
}
