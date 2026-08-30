package com.amadeus.whale.feed

import com.amadeus.whale.model.AmadeusMood
import com.amadeus.whale.network.AmadeusApi
import com.amadeus.whale.network.AmadeusStream
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RealFeedTest {
  private lateinit var server: MockWebServer
  private lateinit var feed: RealFeed

  @Before fun setUp() {
    server = MockWebServer(); server.start()
    val api = AmadeusApi(server.url("/").toString().trimEnd('/'), OkHttpClient())
    val stream = AmadeusStream(server.url("/").toString().trimEnd('/'), OkHttpClient())
    feed = RealFeed("s1", api, stream)
  }
  @After fun tearDown() { server.shutdown() }

  @Test fun initialLoadsLatestState() = runTest {
    server.enqueue(MockResponse().setBody(
      """{"messages":[{"role":"user","text":"帮我改个文件"},{"role":"assistant","text":"好呀\n[[AMW:{\"mood\":\"happy\",\"sprite\":\"wag\"}]]"}],"hasMore":false}"""
    ).addHeader("Content-Type", "application/json"))
    val segs = feed.initial()
    assertEquals(1, segs.size)
    assertEquals("好呀", segs[0].dialog)
    assertEquals(AmadeusMood.happy, segs[0].tag.mood)
  }
}