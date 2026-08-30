package com.amadeus.whale.network

import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AmadeusApiTest {
  private lateinit var server: MockWebServer
  private lateinit var api: AmadeusApi

  @Before fun setUp() {
    server = MockWebServer(); server.start()
    api = AmadeusApi(server.url("/").toString().trimEnd('/'), OkHttpClient())
  }
  @After fun tearDown() { server.shutdown() }

  @Test fun listSessionsParses() = runTest {
    server.enqueue(MockResponse().setBody(
      """{"sessions":[{"id":"s1","title":"今天","mode":"amadeus","updatedAt":1700000000000,"lastMessage":"呜"}]}"""
    ).addHeader("Content-Type", "application/json"))
    val list = api.listSessions()
    assertEquals(1, list.size); assertEquals("s1", list[0].id); assertEquals("今天", list[0].title)
  }

  @Test fun healthTrueWhenCapable() = runTest {
    server.enqueue(MockResponse().setBody(
      """{"capabilities":{"sessions":true,"reports":true,"choices":true}}"""
    ).addHeader("Content-Type", "application/json"))
    assertTrue(api.health())
  }

  @Test fun createSendsWorkspaceId() = runTest {
    server.enqueue(MockResponse().setBody(
      """{"session":{"id":"s2","title":"新会话","mode":"amadeus","updatedAt":1}}"""
    ).addHeader("Content-Type", "application/json"))
    val s = api.createSession(workspaceId = "w1")
    assertEquals("s2", s.id)
    val req = server.takeRequest()
    assertTrue(req.body.readUtf8().contains("\"workspaceId\":\"w1\""))
  }

  @Test fun resolveChoiceSendsChoiceId() = runTest {
    server.enqueue(MockResponse().setBody("""{"ok":true}""")
      .addHeader("Content-Type", "application/json"))
    api.resolveChoice("cq_abc", "继续")
    val body = server.takeRequest().body.readUtf8()
    assertTrue(body.contains("\"choiceId\":\"cq_abc\""))
    assertTrue(body.contains("\"selected\":\"继续\""))
  }
}