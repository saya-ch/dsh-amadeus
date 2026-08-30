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
    // 读档语义：进入已有会话只展示最新态，不重播整段历史（历史浏览交给 HistoryWindow）。
    // 只取最后一条 assistant 消息解析为台词段。
    val lastAssistant = page.messages.filter { it.role == "assistant" }.lastOrNull()?.text.orEmpty()
    return if (lastAssistant.isBlank()) emptyList()
      else AmadeusSegmentParser.parse(lastAssistant)
  }

  fun attach(onEvent: (StreamEvent) -> Unit): AutoCloseable =
    stream.open(sessionId, onEvent)

  suspend fun send(text: String) { api.sendPrompt(sessionId, text) }
  suspend fun cancel() { api.cancel(sessionId) }
}