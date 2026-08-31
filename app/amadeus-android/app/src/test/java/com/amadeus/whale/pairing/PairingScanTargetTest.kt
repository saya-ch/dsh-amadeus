package com.amadeus.whale.pairing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class PairingScanTargetTest {
  private val instanceId = "ab".repeat(32)
  private val token = "A".repeat(43)

  @Test fun parsesPairingUrl() {
    val target = PairingScanTarget.parse(
      "https://192.168.1.20:3444/mobile-access/pair#instance=$instanceId&token=$token"
    )
    assertEquals("192.168.1.20", target.origin.host)
    assertEquals(3444, target.origin.port)
    assertEquals(instanceId, target.instanceId)
    assertEquals(token, target.token)
  }

  @Test fun rejectsBareAppKey() {
    assertThrows(IllegalArgumentException::class.java) {
      PairingScanTarget.parse("dsh1.$instanceId.$token")
    }
  }

  @Test fun rejectsBareOrigin() {
    assertThrows(IllegalArgumentException::class.java) {
      PairingScanTarget.parse("https://192.168.1.20:3444")
    }
  }

  @Test fun rejectsMalformedUrl() {
    assertThrows(IllegalArgumentException::class.java) {
      PairingScanTarget.parse(
        "https://192.168.1.20:3444/mobile-access/pair#instance=$instanceId&token=${"A".repeat(42)}"
      )
    }
  }
}
