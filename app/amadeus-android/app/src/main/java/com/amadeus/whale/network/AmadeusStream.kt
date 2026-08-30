package com.amadeus.whale.network

import com.amadeus.whale.model.AmadeusSegment
import com.amadeus.whale.model.AmadeusSegmentParser
import com.amadeus.whale.model.ChoiceOption
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources

sealed class StreamEvent {
  data class Segments(val list: List<AmadeusSegment>) : StreamEvent()
  data class Choice(val choiceId: String, val question: String, val options: List<ChoiceOption>) : StreamEvent()
  data class Ended(val reason: String) : StreamEvent()
}

class AmadeusStream(private val baseUrl: String, private val client: OkHttpClient) {
  private val json = Json { ignoreUnknownKeys = true }

  fun open(sessionId: String, onEvent: (StreamEvent) -> Unit): AutoCloseable {
    val req = Request.Builder()
      .url("${baseUrl.trimEnd('/')}/amadeus/extensions/amadeus/routes/stream/$sessionId")
      .get().build()
    val factory = EventSources.createFactory(client)
    val source = factory.newEventSource(req, object : EventSourceListener() {
      override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
        val parsed = runCatching { json.parseToJsonElement(data) as JsonObject }.getOrNull() ?: return
        when ((parsed["type"] as? JsonPrimitive)?.content) {
          "segments" -> {
            val text = (parsed["text"] as? JsonPrimitive)?.content ?: return
            onEvent(StreamEvent.Segments(AmadeusSegmentParser.parse(text)))
          }
          "choice" -> {
            val choiceId = (parsed["choiceId"] as? JsonPrimitive)?.content ?: return
            val question = (parsed["question"] as? JsonPrimitive)?.content ?: ""
            val options = (parsed["options"] as? JsonArray)?.mapNotNull {
              val o = it as? JsonObject ?: return@mapNotNull null
              ChoiceOption((o["label"] as? JsonPrimitive)?.contentOrNull ?: "",
                (o["description"] as? JsonPrimitive)?.contentOrNull)
            } ?: emptyList()
            onEvent(StreamEvent.Choice(choiceId, question, options))
          }
          "ended" -> onEvent(StreamEvent.Ended((parsed["reason"] as? JsonPrimitive)?.content ?: ""))
        }
      }
      override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
        onEvent(StreamEvent.Ended(t?.message ?: "stream_failure"))
      }
    })
    return AutoCloseable { source.cancel() }
  }
}