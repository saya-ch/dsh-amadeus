package com.amadeus.whale.network

import com.amadeus.whale.ui.saveslot.SaveSlot
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

/**
 * Amadeus 扩展 JSON 调用草稿，不包含配对、认证客户端或 WebSocket 实现。
 *
 * @param baseUrl dsh-mobile 网关的 HTTPS origin，不是独立 Amadeus 监听端口。
 * @param client 调用方配置的认证客户端，负责 TLS 验证、独立配对与 Cookie 存储；
 * 写请求还需匹配的 Origin、Sec-Fetch-Site 和当前会话的 CSRF 请求头。
 * Amadeus 不能直接读取另一个 App 的配对凭据或 Cookie。
 */
class AmadeusApi(
  private val baseUrl: String,
  private val client: OkHttpClient
) {
  private val routesUrl = "${baseUrl.trimEnd('/')}/mobile-access/extensions/amadeus/routes"

  suspend fun listSessions(mode: String = "amadeus"): List<SaveSlot> = withContext(Dispatchers.IO) {
    require(mode == "amadeus") { "Only amadeus sessions are supported" }
    val req = Request.Builder().url("$routesUrl/sessions?mode=amadeus").get().build()
    client.newCall(req).execute().use { resp ->
      if (!resp.isSuccessful) throw IOException("Amadeus sessions unavailable: HTTP ${resp.code}")
      val body = resp.body?.string() ?: throw IOException("Amadeus sessions response body is missing")
      val arr = JSONObject(body).getJSONArray("sessions")
      (0 until arr.length()).map { i ->
        val o = arr.getJSONObject(i)
        SaveSlot(o.getString("id"), o.getString("title"), o.getLong("updatedAt"), o.optString("lastMessage", null))
      }
    }
  }

  suspend fun createSession(mode: String = "amadeus", title: String? = null): SaveSlot = withContext(Dispatchers.IO) {
    require(mode == "amadeus") { "Only amadeus sessions are supported" }
    val json = JSONObject().apply { if (title != null) put("title", title) }.toString()
    val req = Request.Builder().url("$routesUrl/sessions")
      .post(okhttp3.RequestBody.create(okhttp3.MediaType.parse("application/json"), json)).build()
    client.newCall(req).execute().use { resp ->
      if (!resp.isSuccessful) throw IOException("Amadeus session creation unavailable: HTTP ${resp.code}")
      val body = resp.body?.string() ?: throw IOException("Amadeus session response body is missing")
      val o = JSONObject(body).getJSONObject("session")
      SaveSlot(o.getString("id"), o.getString("title"), o.getLong("updatedAt"))
    }
  }
}
