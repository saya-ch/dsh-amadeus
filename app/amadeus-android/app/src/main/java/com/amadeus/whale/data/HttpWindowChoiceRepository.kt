package com.amadeus.whale.data

import com.amadeus.whale.domain.ChoiceRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** 选项 Repository 的 OkHttp 实现（ask_user_question，产品 1.9）。 */
class HttpChoiceRepository(
  private val baseUrl: String,
  private val client: OkHttpClient,
) : ChoiceRepository {
  private val routes = "${baseUrl.trimEnd('/')}/amadeus/extensions/amadeus/routes"

  override suspend fun resolve(choiceId: String, selected: String): Unit = withContext(Dispatchers.IO) {
    val body = """{"choiceId":${jsonString(choiceId)},"selected":${jsonString(selected)}}"""
    try {
      client.newCall(Request.Builder().url("$routes/choice")
        .post(body.toRequestBody(JSON)).build()).execute().use { res ->
        android.util.Log.d("AMW", "choice resolve ${res.code} id=${choiceId.take(12)} sel=${selected.take(16)}")
        if (!res.isSuccessful) android.util.Log.e("AMW", "choice resolve failed ${res.code}: ${res.body?.string()?.take(120)}")
      }
    } catch (error: Exception) {
      android.util.Log.e("AMW", "choice resolve error ${error.message}")
    }
  }

  override suspend fun cancel(choiceId: String): Unit = withContext(Dispatchers.IO) {
    val body = """{"choiceId":${jsonString(choiceId)}}"""
    try {
      client.newCall(Request.Builder().url("$routes/choice/cancel")
        .post(body.toRequestBody(JSON)).build()).execute().use { res ->
        android.util.Log.d("AMW", "choice cancel ${res.code}")
      }
    } catch (error: Exception) {
      android.util.Log.e("AMW", "choice cancel error ${error.message}")
    }
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

/** 审批 Repository 的 OkHttp 实现（方案 B：POST /approval/:id/decide）。 */
class HttpApprovalRepository(
  private val baseUrl: String,
  private val client: OkHttpClient,
) : com.amadeus.whale.domain.ApprovalRepository {
  private val routes = "${baseUrl.trimEnd('/')}/amadeus/extensions/amadeus/routes"

  override suspend fun decide(approvalId: String, allowed: Boolean) = withContext(Dispatchers.IO) {
    val body = """{"outcome":${if (allowed) "\"allowed-once\"" else "\"rejected\""}}"""
    client.newCall(Request.Builder().url("$routes/approval/$approvalId/decide")
      .post(body.toRequestBody(JSON)).build()).execute().use { }
  }

  private companion object {
    val JSON = "application/json; charset=utf-8".toMediaType()
  }
}
