package com.amadeus.whale.network

import com.amadeus.whale.ui.saveslot.SaveSlot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

/**
 * 复用 dsh-mobile 的 OkHttp + 证书固定 (占位，实际由现有 NetworkModule 提供)
 * 仅负责 /amadeus/* JSON 与 WS 流
 */
class AmadeusApi(
  private val baseUrl: String, // e.g. https://192.168.1.5:3443
  private val client: OkHttpClient
) {
  suspend fun listSessions(mode: String = "amadeus"): List<SaveSlot> = withContext(Dispatchers.IO) {
    val req = Request.Builder().url("$baseUrl/amadeus/sessions?mode=$mode").get().build()
    client.newCall(req).execute().use { resp ->
      val body = resp.body?.string() ?: """{"sessions":[]}"""
      val arr = JSONObject(body).optJSONArray("sessions") ?: JSONArray()
      (0 until arr.length()).map { i ->
        val o = arr.getJSONObject(i)
        SaveSlot(o.getString("id"), o.getString("title"), o.getLong("updatedAt"), o.optString("lastMessage", null))
      }
    }
  }

  suspend fun createSession(mode: String = "amadeus", title: String? = null): SaveSlot = withContext(Dispatchers.IO) {
    val json = JSONObject().apply { if (title != null) put("title", title) }.toString()
    val req = Request.Builder().url("$baseUrl/amadeus/sessions")
      .post(okhttp3.RequestBody.create(okhttp3.MediaType.parse("application/json"), json)).build()
    client.newCall(req).execute().use { resp ->
      val body = resp.body?.string() ?: "{}"
      val o = JSONObject(body).getJSONObject("session")
      SaveSlot(o.getString("id"), o.getString("title"), o.getLong("updatedAt"))
    }
  }

  // WS 流在 TheatreViewModel 中通过 OkHttp WebSocket 复用，解析 [[AMW:]] 后驱动 SpriteRenderer
}
