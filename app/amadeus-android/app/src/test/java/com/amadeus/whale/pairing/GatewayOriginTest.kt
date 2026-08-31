package com.amadeus.whale.pairing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class GatewayOriginTest {
  @Test fun parsesPlainHttpsOrigin() {
    val origin = GatewayOrigin.parse("https://192.168.1.20:3444")
    assertEquals("192.168.1.20", origin.host)
    assertEquals(3444, origin.port)
    assertEquals("https://192.168.1.20:3444", origin.serialized)
  }

  @Test fun omitsDefaultPort() {
    assertEquals("https://dsh.example.com", GatewayOrigin.parse("https://dsh.example.com:443").serialized)
  }

  @Test fun rejectsNonHttps() {
    assertThrows(IllegalArgumentException::class.java) { GatewayOrigin.parse("http://192.168.1.20:3444") }
  }

  @Test fun rejectsPathQueryFragmentAndCredentials() {
    assertThrows(IllegalArgumentException::class.java) { GatewayOrigin.parse("https://h:3444/path") }
    assertThrows(IllegalArgumentException::class.java) { GatewayOrigin.parse("https://h:3444?q=1") }
    assertThrows(IllegalArgumentException::class.java) { GatewayOrigin.parse("https://h:3444#frag") }
    assertThrows(IllegalArgumentException::class.java) { GatewayOrigin.parse("https://user:pass@h:3444") }
  }

  @Test fun rejectsBareHostWithoutScheme() {
    assertThrows(IllegalArgumentException::class.java) { GatewayOrigin.parse("192.168.1.20:3444") }
  }
}
