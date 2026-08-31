package com.amadeus.whale.saveslot

import com.amadeus.whale.network.AmadeusApi
import com.amadeus.whale.network.AmadeusSession
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals

class SaveSlotViewModelTest {
  private lateinit var server: MockWebServer
  private lateinit var vm: SaveSlotViewModel

  @Before fun setUp() {
    server = MockWebServer(); server.start()
    vm = SaveSlotViewModel(AmadeusApi(server.url("/").toString().trimEnd('/'), OkHttpClient()))
  }
  @After fun tearDown() { server.shutdown() }

  @Test fun loadPopulatesList() = runTest {
    server.enqueue(MockResponse().setBody(
      """{"sessions":[{"id":"s1","title":"今天","mode":"amadeus","updatedAt":1}]}"""
    ).addHeader("Content-Type", "application/json"))
    vm.load()
    assertEquals(1, vm.list.value.size)
    assertEquals("今天", vm.list.value[0].title)
  }

  @Test fun archiveSendsRequest() = runTest {
    server.enqueue(MockResponse().setBody("{}"))
    vm.archive("s1")
    val req = server.takeRequest()
    assertEquals("/amadeus/extensions/amadeus/routes/sessions/s1/archive", req.path)
  }
}