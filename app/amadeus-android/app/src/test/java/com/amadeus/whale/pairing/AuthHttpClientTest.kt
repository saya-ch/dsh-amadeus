package com.amadeus.whale.pairing

import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AuthHttpClientTest {
  private lateinit var server: MockWebServer

  @Before fun setUp() { server = MockWebServer(); server.start() }

  @After fun tearDown() { server.shutdown() }

  private fun baseUrl() = server.url("/").toString().trimEnd('/')

  @Test fun storesSessionAndCsrfCookies() {
    val jar = AuthCookieJar()
    jar.store("amw_session=abc123; Path=/; Secure; HttpOnly", "amw_csrf=csrf99; Path=/; Secure")
    assertEquals("abc123", jar.sessionCookie())
    assertEquals("csrf99", jar.csrfCookie())
  }

  @Test fun postAddsCsrfHeader() = runTest {
    val jar = AuthCookieJar()
    jar.store("amw_session=abc123; Path=/; Secure", "amw_csrf=csrf99; Path=/; Secure")
    val client = buildAuthClient(OkHttpClient(), jar)
    server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
    client.newCall(Request.Builder().url(baseUrl()).post("{}".toRequestBody("application/json".toMediaType())).build())
      .execute().use { }
    val recorded = server.takeRequest()
    assertEquals("csrf99", recorded.getHeader("x-amw-csrf"))
    assertTrue(recorded.getHeader("Cookie")?.contains("amw_session=abc123") == true)
  }

  @Test fun getDoesNotAddCsrfHeader() = runTest {
    val jar = AuthCookieJar()
    jar.store("amw_session=abc123; Path=/; Secure", "amw_csrf=csrf99; Path=/; Secure")
    val client = buildAuthClient(OkHttpClient(), jar)
    server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
    client.newCall(Request.Builder().url(baseUrl()).get().build()).execute().use { }
    val recorded = server.takeRequest()
    assertNull(recorded.getHeader("x-amw-csrf"))
  }

  @Test fun clearRemovesCookies() {
    val jar = AuthCookieJar()
    jar.store("amw_session=a; Path=/", "amw_csrf=b; Path=/")
    jar.clear()
    assertNull(jar.sessionCookie())
    assertNull(jar.csrfCookie())
  }
}
