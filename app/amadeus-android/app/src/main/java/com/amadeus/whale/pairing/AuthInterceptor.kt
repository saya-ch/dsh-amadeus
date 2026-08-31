package com.amadeus.whale.pairing

import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response

class AuthInterceptor(private val jar: AuthCookieJar) : Interceptor {
  override fun intercept(chain: Interceptor.Chain): Response {
    val request = chain.request()
    val builder = request.newBuilder()
    if (request.method != "GET" && request.method != "HEAD") {
      jar.csrfCookie()?.let { builder.header("x-amw-csrf", it) }
    }
    return chain.proceed(builder.build())
  }
}

fun buildAuthClient(base: OkHttpClient, jar: AuthCookieJar): OkHttpClient =
  base.newBuilder()
    .cookieJar(jar)
    .addInterceptor(AuthInterceptor(jar))
    .build()
