package com.amadeus.whale.network

import com.amadeus.whale.model.AmadeusMood
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals

class AmadeusStreamTest {
  private lateinit var server: MockWebServer
  private lateinit var stream: AmadeusStream

  @Before fun setUp() {
    server = MockWebServer(); server.start()
    stream = AmadeusStream(server.url("/").toString().trimEnd('/'), OkHttpClient())
  }
  @After fun tearDown() { server.shutdown() }

  @Test fun parsesSegmentEvent() = runTest {
    server.enqueue(MockResponse()
      .setHeader("Content-Type", "text/event-stream")
      .setBody("data: {\"type\":\"segments\",\"text\":\"呜...\\n[[AMW:{\\\"mood\\\":\\\"shy\\\"}]]\"}\n\n"))
    val latch = CountDownLatch(1)
    var got: StreamEvent? = null
    stream.open("s1") { got = it; latch.countDown() }
    assertEquals(true, latch.await(3, TimeUnit.SECONDS))
    val seg = (got as StreamEvent.Segments).list
    assertEquals(1, seg.size)
    assertEquals(AmadeusMood.shy, seg[0].tag.mood)
    val req = server.takeRequest()
    assertEquals("/amadeus/extensions/amadeus/routes/stream/s1", req.path)
  }
}