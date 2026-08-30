package com.amadeus.whale.feed

import com.amadeus.whale.model.AmadeusSegment
import com.amadeus.whale.model.AmadeusSegmentParser
import com.amadeus.whale.network.AmadeusApi
import com.amadeus.whale.network.AmadeusStream
import com.amadeus.whale.network.StreamEvent

class RealFeed(
  private val sessionId: String,
  private val api: AmadeusApi,
  private val stream: AmadeusStream,
) : MessageFeed {

  override suspend fun initial(): List<AmadeusSegment> {
    val page = api.pageSession(sessionId)
    val assistantTexts = page.messages.filter { it.role == "assistant" }
      .map { it.text }.joinToString("\n")
    return if (assistantTexts.isBlank()) emptyList()
      else AmadeusSegmentParser.parse(assistantTexts)
  }

  fun attach(onEvent: (StreamEvent) -> Unit): AutoCloseable =
    stream.open(sessionId, onEvent)

  suspend fun send(text: String) { api.sendPrompt(sessionId, text) }
  suspend fun cancel() { api.cancel(sessionId) }
}