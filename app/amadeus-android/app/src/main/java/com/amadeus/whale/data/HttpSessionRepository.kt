package com.amadeus.whale.data

import com.amadeus.whale.domain.DirectoryListingView
import com.amadeus.whale.domain.SessionRepository
import com.amadeus.whale.domain.WorkspaceView
import com.amadeus.whale.domain.model.AmadeusSession
import com.amadeus.whale.domain.model.AmadeusSprite
import com.amadeus.whale.domain.model.AmadeusTag
import com.amadeus.whale.domain.model.AmadeusVoice
import com.amadeus.whale.domain.model.AmadeusWindow
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

  override suspend fun listWorkspaces(): List<WorkspaceView> = withContext(Dispatchers.IO) {
    val res = client.newCall(Request.Builder().url("$routes/workspaces").get().build()).execute()
    res.use {
      if (!it.isSuccessful) return@withContext emptyList()
      val body = it.body?.string() ?: return@withContext emptyList()
      val arr = (json.parseToJsonElement(body) as? JsonObject)?.get("workspaces") as? JsonArray ?: return@withContext emptyList()
      arr.mapNotNull { w ->
        val wo = w as? JsonObject ?: return@mapNotNull null
        WorkspaceView(
          id = (wo["id"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null,
          path = (wo["path"] as? JsonPrimitive)?.contentOrNull ?: "",
          title = (wo["title"] as? JsonPrimitive)?.contentOrNull ?: "",
        )
      }
    }
  }

  override suspend fun browseDirectory(path: String?): DirectoryListingView = withContext(Dispatchers.IO) {
    val url = "$routes/workspaces/browse" + (path?.let { "?path=${java.net.URLEncoder.encode(it, "UTF-8")}" } ?: "")
    val res = client.newCall(Request.Builder().url(url).get().build()).execute()
    res.use {
      val body = it.body?.string() ?: return@withContext DirectoryListingView("", "", emptyList(), emptyList())
      if (!it.isSuccessful) return@withContext DirectoryListingView("", "", emptyList(), emptyList())
      val obj = (json.parseToJsonElement(body) as? JsonObject)?.get("listing") as? JsonObject ?: return@withContext DirectoryListingView("", "", emptyList(), emptyList())
      val crumbs = (obj["crumbs"] as? JsonArray)?.mapNotNull { c ->
        val co = c as? JsonObject ?: return@mapNotNull null
        Pair((co["name"] as? JsonPrimitive)?.contentOrNull ?: "", (co["path"] as? JsonPrimitive)?.contentOrNull ?: "")
      } ?: emptyList()
      val entries = (obj["entries"] as? JsonArray)?.mapNotNull { e ->
        val eo = e as? JsonObject ?: return@mapNotNull null
        Triple(
          (eo["name"] as? JsonPrimitive)?.contentOrNull ?: "",
          (eo["path"] as? JsonPrimitive)?.contentOrNull ?: "",
          (eo["hidden"] as? JsonPrimitive)?.contentOrNull?.toBoolean() ?: false,
        )
      } ?: emptyList()
      DirectoryListingView(
        path = (obj["path"] as? JsonPrimitive)?.contentOrNull ?: "",
        home = (obj["home"] as? JsonPrimitive)?.contentOrNull ?: "",
        crumbs = crumbs,
        entries = entries,
      )
    }
  }

  override suspend fun registerWorkspace(path: String): WorkspaceView = withContext(Dispatchers.IO) {
    val body = buildString { append("{\"path\":\"").append(path.replace("\\", "\\\\").replace("\"", "\\\"")).append("\"}") }
    val res = client.newCall(
      Request.Builder().url("$routes/workspaces/register")
        .post(body.toRequestBody(JSON_MEDIA_TYPE)).build(),
    ).execute()
    res.use {
      if (!it.isSuccessful) error("register workspace failed: ${it.code}")
      val b = it.body?.string() ?: error("empty body")
      val w = ((json.parseToJsonElement(b) as? JsonObject)?.get("workspace") as? JsonObject) ?: error("bad register response")
      WorkspaceView(
        id = (w["id"] as? JsonPrimitive)?.contentOrNull ?: error("no id"),
        path = (w["path"] as? JsonPrimitive)?.contentOrNull ?: "",
        title = (w["title"] as? JsonPrimitive)?.contentOrNull ?: "",
      )
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
    android.util.Log.d("AMW", "stream: open $sessionId")
    val source = factory.newEventSource(req, object : EventSourceListener() {
      override fun onOpen(eventSource: EventSource, response: Response) {
        android.util.Log.d("AMW", "stream: opened code=${response.code}")
      }
      override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
        android.util.Log.d("AMW", "stream: event data=${data.take(80)}")
        StreamEventParser.parse(data)?.let(onEvent)
      }
      override fun onClosed(eventSource: EventSource) {
        android.util.Log.d("AMW", "stream: closed")
      }
      override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
        android.util.Log.d("AMW", "stream: failure ${t?.message} code=${response?.code}")
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
        else {
          // 剥离段尾 [[AMW:...]] 标签（page 是原始事件，含标签；SSE 流已剥离）
          val clean = stripAmwTag(text)
          val tag = parseAmwTag(ev["data"])
          StreamEvent.DialogueEvent(Dialogue(clean, tag))
        }
      }
      else -> null // tool/think 等历史事件暂不完整映射（可后续扩展）
    }
  }

  private fun extractAssistantText(data: Any?): String? {
    val obj = data as? JsonObject ?: return null
    // DSH 事件 data 结构：assistant/message 是 { message: { content: [...] } }
    val message = obj["message"] as? JsonObject
    val content = (message ?: obj)["content"] as? JsonArray ?: return null
    return content.mapNotNull { c ->
      val co = c as? JsonObject ?: return@mapNotNull null
      if ((co["type"] as? JsonPrimitive)?.contentOrNull == "text") {
        (co["text"] as? JsonPrimitive)?.contentOrNull
      } else null
    }.joinToString("\n")
  }

  /** 剥离段尾 [[AMW:{...}]] 标签（与 Host parseAmadeusTag 一致）。 */
  private fun stripAmwTag(text: String): String {
    val m = Regex("\\[\\[AMW:\\s*(\\{[\\s\\S]*?\\})\\]\\]\\s*$").find(text)
    return m?.let { text.substring(0, it.range.first).trimEnd() } ?: text
  }

  /** 从 assistant/message data 里解析标签（简版：找 content 文本里的 AMW 标签）。 */
  private fun parseAmwTag(data: Any?): AmadeusTag {
    val text = extractAssistantText(data) ?: return AmadeusTag()
    val m = Regex("\\[\\[AMW:\\s*(\\{[\\s\\S]*?\\})\\]\\]").find(text) ?: return AmadeusTag()
    return runCatching {
      val obj = json.parseToJsonElement(m.groupValues[1]).jsonObject
      AmadeusTag(
        sprite = runCatching { AmadeusSprite.valueOf((obj["sprite"] as? JsonPrimitive)?.content ?: "normal") }.getOrDefault(AmadeusSprite.normal),
        voice = runCatching { AmadeusVoice.valueOf((obj["voice"] as? JsonPrimitive)?.content ?: "soft") }.getOrDefault(AmadeusVoice.soft),
        window = runCatching { AmadeusWindow.valueOf((obj["window"] as? JsonPrimitive)?.content ?: "none") }.getOrDefault(AmadeusWindow.none),
        windowId = (obj["windowId"] as? JsonPrimitive)?.content ?: "",
        windowTitle = (obj["windowTitle"] as? JsonPrimitive)?.content ?: "",
      )
    }.getOrDefault(AmadeusTag())
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
