package com.amadeus.whale.data

import com.amadeus.whale.domain.SessionRepository
import com.amadeus.whale.domain.model.AmadeusSession
import com.amadeus.whale.domain.model.AmadeusTag
import com.amadeus.whale.domain.model.Dialogue
import com.amadeus.whale.domain.model.SessionPage
import com.amadeus.whale.domain.model.StreamEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.long
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources

/** 会话 Repository 的 OkHttp 实现（架构 3.6/3.19/3.20）。 */
class HttpSessionRepository(
  private val baseUrl: String,
  private val client: OkHttpClient,
) : SessionRepository {

  private val json = Json { ignoreUnknownKeys = true }
  private val routes = "${baseUrl.trimEnd('/')}/amadeus/extensions/amadeus/routes"

  override suspend fun list(): List<AmadeusSession> = withContext(Dispatchers.IO) {
    val res = client.newCall(Request.Builder().url("$routes/sessions").get().build()).execute()
    res.use {
      if (!it.isSuccessful) return@withContext emptyList()
      val body = it.body?.string() ?: return@withContext emptyList()
      parseSessions(body)
    }
  }

  override suspend fun lastActive(): AmadeusSession? = list().maxByOrNull { it.updatedAt }

  override suspend fun create(workspaceId: String?): AmadeusSession = withContext(Dispatchers.IO) {
    val body = buildString {
      append("{")
      if (workspaceId != null) append("\"workspaceId\":\"$workspaceId\"")
      append("}")
    }
    val res = client.newCall(
      Request.Builder().url("$routes/sessions")
        .post(body.toRequestBody(JSON_MEDIA_TYPE)).build(),
    ).execute()
    res.use {
      if (!it.isSuccessful) error("create failed: ${it.code}")
      val b = it.body?.string() ?: error("empty body")
      val obj = json.parseToJsonElement(b).jsonObject
      parseSession(obj["session"] as? JsonObject ?: obj) ?: error("bad create response")
    }
  }

  override suspend fun rename(sessionId: String, title: String) = withContext(Dispatchers.IO) {
    val body = """{"title":${jsonString(title)}}"""
    client.newCall(Request.Builder().url("$routes/sessions/$sessionId")
      .patch(body.toRequestBody(JSON_MEDIA_TYPE)).build()).execute().use { }
  }

  override suspend fun archive(sessionId: String) = withContext(Dispatchers.IO) {
    client.newCall(Request.Builder().url("$routes/sessions/$sessionId")
      .delete().build()).execute().use { }
  }

  override suspend fun send(sessionId: String, text: String) = withContext(Dispatchers.IO) {
    val body = """{"text":${jsonString(text)}}"""
    client.newCall(Request.Builder().url("$routes/sessions/$sessionId/prompt")
      .post(body.toRequestBody(JSON_MEDIA_TYPE)).build()).execute().use { }
  }

  override suspend fun cancel(sessionId: String) = withContext(Dispatchers.IO) {
    client.newCall(Request.Builder().url("$routes/sessions/$sessionId/cancel")
      .post("".toRequestBody(JSON_MEDIA_TYPE)).build()).execute().use { }
  }

  override suspend fun page(sessionId: String, beforeSeq: Long?): SessionPage = withContext(Dispatchers.IO) {
    val url = "$routes/sessions/$sessionId/page" + (beforeSeq?.let { "?beforeSeq=$it" } ?: "")
    val res = client.newCall(Request.Builder().url(url).get().build()).execute()
    res.use {
      if (!it.isSuccessful) return@withContext SessionPage(emptyList(), false)
      val body = it.body?.string() ?: return@withContext SessionPage(emptyList(), false)
      parsePage(body)
    }
  }

  override fun openStream(sessionId: String, onEvent: (StreamEvent) -> Unit): AutoCloseable {
    val req = Request.Builder().url("$routes/stream/$sessionId").get().build()
    val factory = EventSources.createFactory(client)
    val source = factory.newEventSource(req, object : EventSourceListener() {
      override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
        StreamEventParser.parse(data)?.let(onEvent)
      }
      override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
        onEvent(StreamEvent.Ended(t?.message ?: "stream_failure"))
      }
    })
    return AutoCloseable { source.cancel() }
  }

  private fun parseSessions(body: String): List<AmadeusSession> {
    val arr = (json.parseToJsonElement(body) as? JsonObject)?.get("sessions") as? JsonArray ?: return emptyList()
    return arr.mapNotNull { parseSession(it as? JsonObject) }
  }

  private fun parseSession(o: JsonObject?): AmadeusSession? {
    if (o == null) return null
    fun str(k: String) = (o[k] as? JsonPrimitive)?.contentOrNull ?: ""
    return AmadeusSession(
      id = str("id"),
      title = str("title"),
      workspace = str("workspace"),
      updatedAt = (o["updatedAt"] as? JsonPrimitive)?.long ?: 0L,
    )
  }

  private fun parsePage(body: String): SessionPage {
    val obj = json.parseToJsonElement(body) as? JsonObject ?: return SessionPage(emptyList(), false)
    val hasMore = (obj["hasMore"] as? JsonPrimitive)?.contentOrNull?.toBoolean() ?: false
    val records = (obj["records"] as? JsonArray) ?: return SessionPage(emptyList(), hasMore)
    val events = records.mapNotNull { rec ->
      val r = rec as? JsonObject ?: return@mapNotNull null
      val ev = r["event"] as? JsonObject ?: return@mapNotNull null
      mapPageEvent(ev)
    }
    return SessionPage(events, hasMore)
  }

  private fun mapPageEvent(ev: JsonObject): StreamEvent? {
    val type = (ev["type"] as? JsonPrimitive)?.contentOrNull ?: return null
    return when (type) {
      "assistant/message" -> {
        val text = extractAssistantText(ev["data"])
        if (text.isNullOrBlank()) null
        else StreamEvent.DialogueEvent(Dialogue(text, AmadeusTag()))
      }
      else -> null // tool/think 等历史事件暂不完整映射（可后续扩展）
    }
  }

  private fun extractAssistantText(data: Any?): String? {
    val obj = data as? JsonObject ?: return null
    val content = obj["content"] as? JsonArray ?: return null
    return content.mapNotNull { c ->
      val co = c as? JsonObject ?: return@mapNotNull null
      if ((co["type"] as? JsonPrimitive)?.contentOrNull == "text") {
        (co["text"] as? JsonPrimitive)?.contentOrNull
      } else null
    }.joinToString("\n")
  }

  private companion object {
    val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

    /** 简单 JSON 字符串转义（避免序列化依赖）。 */
    fun jsonString(s: String): String = buildString {
      append('"')
      s.forEach { c ->
        when (c) {
          '"' -> append("\\\"")
          '\\' -> append("\\\\")
          '\n' -> append("\\n")
          '\r' -> append("\\r")
          '\t' -> append("\\t")
          else -> append(c)
        }
      }
      append('"')
    }
  }
}
