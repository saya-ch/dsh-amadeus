package com.amadeus.whale.pairing

import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl

class AuthCookieJar : CookieJar {
  private var session: String? = null
  private var csrf: String? = null

  fun store(sessionHeader: String, csrfHeader: String) {
    session = extractValue(sessionHeader, "amw_session")
    csrf = extractValue(csrfHeader, "amw_csrf")
  }

  fun sessionCookie(): String? = session

  fun csrfCookie(): String? = csrf

  fun clear() {
    session = null
    csrf = null
  }

  override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
    for (cookie in cookies) {
      when (cookie.name.lowercase()) {
        "amw_session" -> session = cookie.value
        "amw_csrf" -> csrf = cookie.value
      }
    }
  }

  override fun loadForRequest(url: HttpUrl): List<Cookie> {
    val result = mutableListOf<Cookie>()
    session?.let { result.add(buildCookie(url, "amw_session", it)) }
    csrf?.let { result.add(buildCookie(url, "amw_csrf", it)) }
    return result
  }

  private fun extractValue(header: String, cookieName: String): String? {
    if (header.isEmpty()) return null
    val lowerHeader = header.lowercase()
    val lowerNeedle = cookieName.lowercase() + "="
    val idx = lowerHeader.indexOf(lowerNeedle)
    if (idx == -1) return null
    val start = idx + lowerNeedle.length
    var end = header.indexOf(';', start)
    if (end == -1) end = header.length
    val value = header.substring(start, end).trim()
    return value.takeIf { it.isNotEmpty() }
  }

  private fun buildCookie(url: HttpUrl, name: String, value: String): Cookie {
    // Try domain() first (spec example), fallback to hostOnlyDomain for IP hosts.
    return try {
      Cookie.Builder().name(name).value(value).domain(url.host).build()
    } catch (_: IllegalArgumentException) {
      Cookie.Builder().name(name).value(value).hostOnlyDomain(url.host).build()
    }
  }
}
