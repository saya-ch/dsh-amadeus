package com.amadeus.whale.network

import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

class AmadeusApiException(val code: Int, val apiMessage: String) :
  IOException("amadeus $code: $apiMessage")

class AmadeusApi(private val baseUrl: String, private val client: OkHttpClient) {
  private val json = Json { ignoreUnknownKeys = true }
  private val routes = "${baseUrl.trimEnd('/')}/amadeus/extensions/amadeus/routes"
  private val jsonType = "application/json".toMediaType()

  private suspend fun <T> call(request: Request, parse: (String) -> T): T = withContext(Dispatchers.IO) {
    client.newCall(request).execute().use { resp ->
      val body = resp.body?.string().orEmpty()
      if (!resp.isSuccessful) throw AmadeusApiException(resp.code, body)
      parse(body)
    }
  }

  suspend fun health(): Boolean =
    call(Request.Builder().url("$routes/status").get().build()) { s ->
      json.decodeFromString<StatusPayload>(s).capabilities.sessions
    }

  suspend fun listSessions(): List<AmadeusSession> =
    call(Request.Builder().url("$routes/sessions?mode=amadeus").get().build()) { s ->
      json.decodeFromString<SessionListPayload>(s).sessions
    }

  suspend fun createSession(title: String? = null, workspaceId: String? = null): AmadeusSession =
    call(Request.Builder().url("$routes/sessions").post(
      json.encodeToString(SessionCreateBody(title, workspaceId)).toRequestBody(jsonType)
    ).build()) { s -> json.decodeFromString<SessionCreatePayload>(s).session }

  suspend fun renameSession(id: String, title: String): Unit =
    call(Request.Builder().url("$routes/sessions/$id/rename").post(
      json.encodeToString(RenameBody(title)).toRequestBody(jsonType)
    ).build()) { }

  suspend fun archiveSession(id: String): Unit =
    call(Request.Builder().url("$routes/sessions/$id/archive").post(
      "{}".toRequestBody(jsonType)
    ).build()) { }

  suspend fun sendPrompt(id: String, text: String): Unit =
    call(Request.Builder().url("$routes/sessions/$id/prompt").post(
      json.encodeToString(PromptBody(text)).toRequestBody(jsonType)
    ).build()) { }

  suspend fun cancel(id: String): Unit =
    call(Request.Builder().url("$routes/sessions/$id/cancel").post(
      "{}".toRequestBody(jsonType)
    ).build()) { }

  suspend fun getReport(id: String): ReportPayload =
    call(Request.Builder().url("$routes/reports/$id").get().build()) { s ->
      json.decodeFromString<ReportPayload>(s)
    }

  suspend fun getPreview(id: String): PreviewPayload =
    call(Request.Builder().url("$routes/previews/$id").get().build()) { s ->
      json.decodeFromString<PreviewPayload>(s)
    }

  suspend fun resolveChoice(choiceId: String, selected: String): Unit =
    call(Request.Builder().url("$routes/choice").post(
      json.encodeToString(ChoiceResolveBody(choiceId, selected)).toRequestBody(jsonType)
    ).build()) { }

  suspend fun pageSession(id: String, beforeSeq: Long? = null): SessionPagePayload =
    call(Request.Builder().url("$routes/sessions/$id/page${beforeSeq?.let { "?beforeSeq=$it" }.orEmpty()}").get().build()) { s ->
      json.decodeFromString<SessionPagePayload>(s)
    }
}