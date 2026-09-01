package com.amadeus.whale.data

import com.amadeus.whale.domain.ChoiceRepository
import com.amadeus.whale.domain.PreviewView
import com.amadeus.whale.domain.ReportView
import com.amadeus.whale.domain.WindowRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.long
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** 窗口 Repository 的 OkHttp 实现（报告/预览，产品 1.9）。 */
class HttpWindowRepository(
  private val baseUrl: String,
  private val client: OkHttpClient,
) : WindowRepository {
  private val json = Json { ignoreUnknownKeys = true }
  private val routes = "${baseUrl.trimEnd('/')}/amadeus/extensions/amadeus/routes"

  override suspend fun report(reportId: String): ReportView = withContext(Dispatchers.IO) {
    val res = client.newCall(Request.Builder().url("$routes/reports/$reportId").get().build()).execute()
    res.use {
      if (!it.isSuccessful) error("report failed: ${it.code}")
      val obj = json.parseToJsonElement(it.body?.string() ?: "{}") as JsonObject
      ReportView(
        id = (obj["id"] as? JsonPrimitive)?.contentOrNull ?: reportId,
        title = (obj["title"] as? JsonPrimitive)?.contentOrNull ?: "",
        markdown = (obj["markdown"] as? JsonPrimitive)?.contentOrNull ?: "",
        createdAt = (obj["createdAt"] as? JsonPrimitive)?.long ?: 0L,
      )
    }
  }

  override suspend fun preview(previewId: String): PreviewView = withContext(Dispatchers.IO) {
    val res = client.newCall(Request.Builder().url("$routes/previews/$previewId").get().build()).execute()
    res.use {
      if (!it.isSuccessful) error("preview failed: ${it.code}")
      val obj = json.parseToJsonElement(it.body?.string() ?: "{}") as JsonObject
      PreviewView(
        id = (obj["id"] as? JsonPrimitive)?.contentOrNull ?: previewId,
        type = (obj["type"] as? JsonPrimitive)?.contentOrNull ?: "text",
        content = (obj["content"] as? JsonPrimitive)?.contentOrNull ?: "",
        title = (obj["title"] as? JsonPrimitive)?.contentOrNull ?: "",
      )
    }
  }
}

/** 选项 Repository 的 OkHttp 实现（ask_user_question，产品 1.9）。 */
class HttpChoiceRepository(
  private val baseUrl: String,
  private val client: OkHttpClient,
) : ChoiceRepository {
  private val routes = "${baseUrl.trimEnd('/')}/amadeus/extensions/amadeus/routes"
  private val json = Json { ignoreUnknownKeys = true }

  override suspend fun resolve(choiceId: String, selected: String) = withContext(Dispatchers.IO) {
    val body = """{"choiceId":${jsonString(choiceId)},"selected":${jsonString(selected)}}"""
    client.newCall(Request.Builder().url("$routes/choice")
      .post(body.toRequestBody(JSON)).build()).execute().use { }
  }

  override suspend fun cancel(choiceId: String) = withContext(Dispatchers.IO) {
    val body = """{"choiceId":${jsonString(choiceId)}}"""
    client.newCall(Request.Builder().url("$routes/choice/cancel")
      .post(body.toRequestBody(JSON)).build()).execute().use { }
  }

  private companion object {
    val JSON = "application/json; charset=utf-8".toMediaType()

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
