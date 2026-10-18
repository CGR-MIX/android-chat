package com.ddzhilian.chat

import java.io.IOException
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import kotlin.coroutines.resumeWithException
import okhttp3.Callback
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

import org.json.JSONObject

private val aiJsonMediaType = "application/json; charset=utf-8".toMediaType()

/**
 * 管理员会话只保存在内存里：进程结束（用户杀进程）就失效，账号密码与 cookie 都不落盘。
 * 这与浏览器后台的 cookie 会话语义一致，也会让「隐秘入口」不会因为旧 cookie 被长期打开。
 */
class MemoryCookieJar : CookieJar {
    private val store = mutableMapOf<String, MutableList<Cookie>>()

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        synchronized(store) {
            val bucket = store.getOrPut(url.host) { mutableListOf() }
            cookies.forEach { cookie ->
                bucket.removeAll { it.name == cookie.name && it.path == cookie.path }
                if (cookie.expiresAt > System.currentTimeMillis()) bucket.add(cookie)
            }
        }
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> = synchronized(store) {
        val now = System.currentTimeMillis()
        store[url.host].orEmpty().filter { it.matches(url) && it.expiresAt > now }
    }

    fun clear() = synchronized(store) { store.clear() }

    fun isEmpty(): Boolean = synchronized(store) { store.values.all { it.isEmpty() } }
}

/** 管理员历史文件列表的游标，与服务端 `listAllFiles` 的分页游标同构。 */
data class AdminHistoryCursor(val createdAt: String, val historyId: String)

/** 打开管理员模式后缓存的一份后台快照，字段与服务端 `/api/admin/state` 对齐。 */
data class AdminDashboard(
    val email: String,
    val isSuperAdmin: Boolean,
    val ai: JSONObject,
    val history: JSONObject,
)

fun parseAdminDashboard(payload: JSONObject): AdminDashboard {
    val admin = payload.optJSONObject("admin") ?: JSONObject()
    return AdminDashboard(
        email = admin.optString("email", "管理员"),
        isSuperAdmin = admin.optBoolean("isSuperAdmin", false),
        ai = payload.optJSONObject("ai") ?: JSONObject(),
        history = payload.optJSONObject("history") ?: JSONObject(),
    )
}

/**
 * AI 相关的两套接口：
 * - 设备侧（branding / chat）沿用历史接口的 `Authorization: Bearer <historyAuthToken>`。
 * - 管理侧完全依赖 `ddzhilian_admin_session` cookie，与网页后台读写同一份配置。
 */
class AiClient(
    private val address: ServerAddress,
    private val client: OkHttpClient,
) {
    private val cookies = MemoryCookieJar()
    private val adminClient = client.newBuilder().cookieJar(cookies).build()

    suspend fun branding(): AiBranding {
        val request = Request.Builder().url(address.api("api/ai/branding")).get().build()
        return client.newCall(request).awaitResponse().use { response ->
            ensureSuccessful(response)
            parseAiBranding(JSONObject(response.body?.string().orEmpty().ifBlank { "{}" }))
        }
    }

    /** 发送一次 AI 提问；回复由服务端写进房间历史，这里只返回文本用于提示。 */
    suspend fun chat(token: String, roomId: String, prompt: String, replyToName: String): String {
        val payload = JSONObject()
            .put("prompt", prompt)
            .put("roomId", roomId)
            .put("replyToName", replyToName)
            .put("historyId", UUID.randomUUID().toString())
            .put("createdAt", Instant.now().toString())
            .put("kind", "chat")
        val request = Request.Builder().url(address.api("api/ai/chat"))
            .header("Authorization", "Bearer " + token)
            .post(payload.toString().toRequestBody(aiJsonMediaType))
            .build()
        return client.newCall(request).awaitResponse().use { response ->
            ensureSuccessful(response)
            val body = JSONObject(response.body?.string().orEmpty().ifBlank { "{}" })
            body.optString("response").trim().ifEmpty { throw IOException("AI 返回了空结果。") }
        }
    }

    suspend fun adminLogin(email: String, password: String): JSONObject {
        val payload = JSONObject().put("email", email.trim()).put("password", password)
        return adminExecute(adminPost("api/admin/login", payload))
    }

    suspend fun adminLogout() {
        runCatching { adminExecute(adminPost("api/admin/logout", JSONObject())) }
        cookies.clear()
    }

    suspend fun adminState(): JSONObject = adminExecute(adminGet("api/admin/state"))

    suspend fun saveAiConfig(payload: JSONObject): JSONObject =
        adminExecute(adminPost("api/admin/ai-config", payload))

    suspend fun historyFiles(limit: Int = 50, cursor: AdminHistoryCursor? = null): JSONObject {
        var url = address.api("api/admin/history/files").newBuilder()
            .addQueryParameter("limit", limit.toString())
        if (cursor != null) {
            url = url
                .addQueryParameter("cursorCreatedAt", cursor.createdAt)
                .addQueryParameter("cursorHistoryId", cursor.historyId)
        }
        return adminExecute(adminGet(url.build().toString()))
    }

    suspend fun deleteHistory(kind: String, historyId: String): JSONObject = adminExecute(
        adminPost("api/admin/history/delete", JSONObject().put("kind", kind).put("historyId", historyId)),
    )

    suspend fun clearHistory(): JSONObject = adminExecute(adminPost("api/admin/history/clear", JSONObject()))

    suspend fun uploadAvatar(bytes: ByteArray, mimeType: String): JSONObject = adminExecute(
        adminBuilder("api/admin/ai-avatar").post(bytes.toRequestBody(mimeType.toMediaType())).build(),
    )

    suspend fun deleteAvatar(): JSONObject = adminExecute(
        adminBuilder("api/admin/ai-avatar").delete().build(),
    )

    private fun adminBuilder(path: String) = Request.Builder().url(address.api(path))

    private fun adminGet(path: String) = adminBuilder(path).get().build()

    private fun adminPost(path: String, payload: JSONObject) =
        adminBuilder(path).post(payload.toString().toRequestBody(aiJsonMediaType)).build()

    private suspend fun adminExecute(request: Request): JSONObject =
        adminClient.newCall(request).awaitResponse().use { response ->
            ensureSuccessful(response)
            JSONObject(response.body?.string().orEmpty().ifBlank { "{}" })
        }
    private fun ensureSuccessful(response: Response) {
        if (response.isSuccessful) return
        val message = runCatching {
            JSONObject(response.body?.string().orEmpty()).optString("error")
        }.getOrNull().orEmpty()
        if (response.code == 401 || response.code == 403) {
            cookies.clear()
        }
        throw IOException("请求失败 (" + response.code + ")：" + message.take(220).ifBlank { "服务暂不可用" })
    }
}

private suspend fun Call.awaitResponse(): Response = suspendCancellableCoroutine { continuation ->
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (continuation.isActive) continuation.resumeWithException(e)
        }

        override fun onResponse(call: Call, response: Response) {
            continuation.resume(response) { _, value, _ -> value.close() }
        }
    })
}
