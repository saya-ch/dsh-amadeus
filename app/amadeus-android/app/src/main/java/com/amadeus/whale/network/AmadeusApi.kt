package com.amadeus.whale.network

import com.amadeus.whale.ui.saveslot.SaveSlot
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/**
 * Amadeus 独立网关 JSON 调用。独立配对：App 自行完成 TLS 固定、配对与 Cookie。
 *
 * @param baseUrl Amadeus 独立网关 origin（如 LAN https://192.168.x.x:3444）。
 * @param client 认证客户端，负责 TLS 验证、配对与 Cookie/CSRF。
 */
class AmadeusApi(
  private val baseUrl: String,
  private val client: OkHttpClient
) {
  // 独立网关：/amadeus/extensions/amadeus/routes
  private val routesUrl = "${baseUrl.trimEnd('/')}/amadeus/extensions/amadeus/routes"

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
      .post(json.toRequestBody("application/json".toMediaType())).build()
    client.newCall(req).execute().use { resp ->
      if (!resp.isSuccessful) throw IOException("Amadeus session creation unavailable: HTTP ${resp.code}")
      val body = resp.body?.string() ?: throw IOException("Amadeus session response body is missing")
      val o = JSONObject(body).getJSONObject("session")
      SaveSlot(o.getString("id"), o.getString("title"), o.getLong("updatedAt"))
    }
  }
}
