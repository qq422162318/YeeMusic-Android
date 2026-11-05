package com.yeemusic.android.api

import com.yeemusic.android.BuildConfig
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody

class ApiException(message: String, val code: Int = 0) : Exception(message)

/** Trim whitespace and trailing slashes; keeps an empty string empty. */
fun normalizeApiUrl(url: String): String = url.trim().trimEnd('/')

/**
 * HTTP client for the NeteaseCloudMusicAPI Enhanced backend.
 *
 * This mirrors the desktop app's request contract exactly, which matters more
 * than it looks: the backend expects `timestamp` and `cookie` as *query
 * parameters* for GET, and merged into the JSON body for POST/PUT. Deviating
 * from that shape breaks endpoints silently.
 */
class ApiClient(private val cookieProvider: suspend () -> String?) {

    @Volatile
    var baseUrl: String = normalizeApiUrl(BuildConfig.DEFAULT_API_BASE_URL)

    /** Fired when the API answers 301 — the cookie has expired upstream. */
    var onRedirect: (() -> Unit)? = null

    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .followRedirects(false)
        .build()

    suspend fun get(path: String, params: Map<String, String> = emptyMap()): JsonElement =
        request("GET", path, params)

    suspend fun post(path: String, params: Map<String, String> = emptyMap()): JsonElement =
        request("POST", path, params)

    suspend fun put(path: String, params: Map<String, String> = emptyMap()): JsonElement =
        request("PUT", path, params)

    suspend fun delete(path: String): JsonElement = request("DELETE", path, emptyMap())

    suspend fun upload(
        path: String,
        multipart: RequestBody,
        extra: Map<String, String> = emptyMap(),
    ): JsonElement = request("POST", path, extra, multipart = multipart)

    /** For endpoints that take nested JSON, e.g. `playlist/update`. */
    suspend fun postJson(path: String, body: JsonElement): JsonElement =
        request("POST", path, emptyMap(), jsonBody = body)

    // -- internals ---------------------------------------------------------

    private suspend fun request(
        method: String,
        path: String,
        params: Map<String, String>,
        multipart: RequestBody? = null,
        jsonBody: JsonElement? = null,
    ): JsonElement {
        val cookie = cookieProvider()?.takeIf { it.isNotBlank() }
        val timestamp = System.currentTimeMillis().toString()
        val useJsonBody = (method == "POST" || method == "PUT") && multipart == null

        // GET/DELETE: everything rides on the query string.
        // POST/PUT: the backend reads the JSON body, so params go there
        // instead of (not in addition to) the query string.
        val query = LinkedHashMap<String, String>()
        if (!useJsonBody) {
            query["timestamp"] = timestamp
            params.forEach { (k, v) -> if (v.isNotBlank()) query[k] = v }
            if (cookie != null) query["cookie"] = cookie
        }

        val body: RequestBody? = when {
            multipart != null -> multipart
            jsonBody != null -> buildJsonObject {
                if (jsonBody is JsonObject) jsonBody.forEach { (k, v) -> put(k, v) }
                if (cookie != null) put("cookie", cookie)
                put("timestamp", timestamp)
            }.toString().toRequestBody(JSON_MEDIA)
            useJsonBody -> buildJsonObject {
                params.forEach { (k, v) -> put(k, v) }
                if (cookie != null) put("cookie", cookie)
                put("timestamp", timestamp)
            }.toString().toRequestBody(JSON_MEDIA)
            else -> null
        }

        val base = normalizeApiUrl(baseUrl).ifBlank {
            throw ApiException("API 服务地址未配置，请在 设置 → 网络 中填写")
        }
        val urlBuilder = (base.trimEnd('/') + path).toHttpUrlOrNull()?.newBuilder()
            ?: throw ApiException("API 地址无效：$base")
        query.forEach { (k, v) -> urlBuilder.addQueryParameter(k, v) }

        val request = Request.Builder()
            .url(urlBuilder.build())
            .method(method, body)
            .header("Accept", "application/json, text/plain, */*")
            .apply { if (cookie != null) header("Cookie", cookie) }
            .build()

        // execute() blocks on the socket. Every caller runs on the main
        // dispatcher, so the I/O has to move off it — otherwise a slow or
        // unreachable node hangs the UI thread for the 15s connect timeout and
        // the system kills the app, which looks exactly like a crash on open.
        return withContext(Dispatchers.IO) {
            http.newCall(request).execute().use { response ->
                if (response.code == 301) onRedirect?.invoke()

                val text = response.body?.string().orEmpty()
                if (!response.isSuccessful) throw ApiException(messageOf(text, response.code), response.code)

                try {
                    JSON.parseToJsonElement(text)
                } catch (e: Exception) {
                    throw ApiException("API 返回了无法解析的内容：${text.take(80)}")
                }
            }
        }
    }

    private fun messageOf(body: String, code: Int): String = try {
        val obj = JSON.parseToJsonElement(body).jsonObject
        obj["message"]?.jsonPrimitive?.content
            ?: obj["msg"]?.jsonPrimitive?.content
            ?: "请求失败：$code"
    } catch (e: Exception) {
        "请求失败：$code"
    }

    /**
     * The upstream response smears cookie attributes (domain=, path=, ...)
     * into the header string. Storing them as key=value pairs corrupts the
     * cookie, so they are dropped before persistence.
     */
    fun sanitizeCookie(cookie: String): String {
        val attrs = setOf(
            "domain", "expires", "httponly", "max-age", "partitioned",
            "path", "priority", "samesite", "secure",
        )
        val pairs = LinkedHashMap<String, String>()
        for (raw in cookie.split(";")) {
            val part = raw.trim()
            val i = part.indexOf('=')
            if (i <= 0) continue
            val name = part.take(i).trim()
            val value = part.substring(i + 1).trim()
            if (name.isEmpty() || value.isEmpty()) continue
            if (name.lowercase() in attrs) continue
            pairs[name] = value
        }
        return pairs.entries.joinToString("; ") { "${it.key}=${it.value}" }
    }
}

/** Shared lenient decoder. The upstream API is loose and changes without notice. */
@OptIn(ExperimentalSerializationApi::class)
val JSON: Json = Json {
    ignoreUnknownKeys = true
    isLenient = true
    coerceInputValues = true
    explicitNulls = false
}

val JSON_MEDIA = "application/json; charset=utf-8".toMediaTypeOrNull()

inline fun <reified T> JsonElement.decode(): T = JSON.decodeFromJsonElement(this)

/** Keep model decoding off the UI thread as well as the socket work. */
suspend inline fun <reified T> ApiClient.getT(path: String, params: Map<String, String> = emptyMap()): T {
    val response = get(path, params)
    return withContext(Dispatchers.Default) { response.decode<T>() }
}

suspend inline fun <reified T> ApiClient.postT(path: String, params: Map<String, String> = emptyMap()): T {
    val response = post(path, params)
    return withContext(Dispatchers.Default) { response.decode<T>() }
}

/** Reads the nested object at [key], falling back to [default]. */
fun JsonObject.getObj(key: String, default: JsonObject = JsonObject(emptyMap())): JsonObject =
    this[key] as? JsonObject ?: default
