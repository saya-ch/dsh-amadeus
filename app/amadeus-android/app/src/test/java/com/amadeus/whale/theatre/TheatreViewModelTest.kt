package com.amadeus.whale.theatre

import com.amadeus.whale.feed.DemoFeed
import com.amadeus.whale.feed.MessageFeed
import com.amadeus.whale.feed.RealFeed
import com.amadeus.whale.model.AmadeusMood
import com.amadeus.whale.model.AmadeusSegment
import com.amadeus.whale.model.AmadeusTag
import com.amadeus.whale.model.AmadeusWindow
import com.amadeus.whale.model.ChoiceOption
import com.amadeus.whale.network.AmadeusApi
import com.amadeus.whale.network.AmadeusStream
import com.amadeus.whale.network.StreamEvent
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TheatreViewModelTest {
  private fun vm() = TheatreViewModel(DemoFeed(), backgroundResolver = { "palace-night" })

  private class StaticFeed(private val segs: List<AmadeusSegment>) : MessageFeed {
    override suspend fun initial(): List<AmadeusSegment> = segs
  }

  private fun seg(dialog: String, mood: AmadeusMood = AmadeusMood.idle) =
    AmadeusSegment(dialog, AmadeusTag(mood = mood))

  @Test fun loadSetsFirstSegment() = runTest {
    val v = vm()
    v.load()
    assertEquals("呜... 月光照在礁石上呢...", v.uiState.value.dialog)
  }

  @Test fun tapAdvancesOneAtATime() = runTest {
    val v = vm()
    v.load()
    val first = v.uiState.value.dialog
    v.onTap() // 第一次点击：完成打字
    assertEquals(first, v.uiState.value.dialog) // 句子不变，仅打字完成
    v.onTap() // 第二次点击：下一句
    assertTrue(v.uiState.value.dialog != first)
  }

  @Test fun skipToEndJumpsToLast() = runTest {
    val v = vm()
    v.load()
    v.skipToEnd()
    val segs = DemoFeed().initial()
    assertEquals(segs.last().dialog, v.uiState.value.dialog)
    assertFalse(v.hasNext())
  }

  @Test fun enqueueAppendsAndShowsWhenIdle() = runTest {
    val v = TheatreViewModel(StaticFeed(emptyList()), { "palace-night" })
    v.enqueue(seg("第一句"))
    assertEquals("第一句", v.uiState.value.dialog)
    assertFalse(v.hasNext())
    v.enqueue(seg("第二句"))
    assertEquals("第一句", v.uiState.value.dialog) // 当前句不被抢占
    assertTrue(v.hasNext())
    v.onTap(); v.onTap()
    assertEquals("第二句", v.uiState.value.dialog)
  }

  @Test fun showChoiceSetsChoiceInUiState() = runTest {
    val v = vm()
    v.showChoice(StreamEvent.Choice("cq_1", "继续吗？", listOf(ChoiceOption("继续"), ChoiceOption("停下"))))
    assertEquals(ChoiceUi("cq_1", "继续吗？", listOf("继续", "停下")), v.uiState.value.choice)
  }

  @Test fun dismissChoiceClearsChoice() = runTest {
    val v = vm()
    v.showChoice(StreamEvent.Choice("cq_1", "继续吗？", listOf(ChoiceOption("继续"))))
    assertTrue(v.uiState.value.choice != null)
    v.dismissChoice()
    assertEquals(null, v.uiState.value.choice)
  }

  @Test fun loadExposesWindowTypeFromTag() = runTest {
    val seg = AmadeusSegment(
      "报告好了",
      AmadeusTag(window = AmadeusWindow.report, windowId = "rpt_1", windowTitle = "报告"),
      "rpt_1",
    )
    val v = TheatreViewModel(StaticFeed(listOf(seg)), { "palace-night" })
    v.load()
    assertEquals("rpt_1", v.uiState.value.windowId)
    assertEquals(AmadeusWindow.report, v.uiState.value.windowType)
  }

  @Test fun windowTypeNullWhenNoWindow() = runTest {
    val v = vm()
    v.load()
    assertEquals(null, v.uiState.value.windowType)
  }

  @Test fun markIdleSetsIdleAndTypingFinished() = runTest {
    val v = vm()
    v.load()
    v.markIdle()
    assertEquals(AmadeusMood.idle, v.uiState.value.mood)
    assertTrue(v.uiState.value.typingFinished)
  }

  @Test fun backgroundResolverForExposesResolver() {
    val resolver: (AmadeusMood) -> String = {
      if (it == AmadeusMood.tool || it == AmadeusMood.think) "bg-gpt-collaboration-workshop"
      else "bg-claude-writing-study"
    }
    val v = TheatreViewModel(DemoFeed(), resolver)
    assertEquals("bg-gpt-collaboration-workshop", v.backgroundResolverFor(AmadeusMood.tool))
    assertEquals("bg-gpt-collaboration-workshop", v.backgroundResolverFor(AmadeusMood.think))
    assertEquals("bg-claude-writing-study", v.backgroundResolverFor(AmadeusMood.idle))
    assertEquals("bg-claude-writing-study", v.backgroundResolverFor(AmadeusMood.happy))
  }

  @Test fun sendToFeedForwardsToRealFeed() = runTest {
    val server = MockWebServer(); server.start()
    try {
      val api = AmadeusApi(server.url("/").toString().trimEnd('/'), OkHttpClient())
      val stream = AmadeusStream(server.url("/").toString().trimEnd('/'), OkHttpClient())
      val realFeed = RealFeed("s1", api, stream)
      server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
      val v = TheatreViewModel(realFeed, { "bg-claude-writing-study" })
      v.sendToFeed("你好呀")
      val req = server.takeRequest()
      assertTrue(req.body.readUtf8().contains("\"text\":\"你好呀\""))
    } finally {
      server.shutdown()
    }
  }
}