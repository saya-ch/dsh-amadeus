package com.amadeus.whale.pairing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class PairingKeyTest {
  private val instanceId = "a".repeat(64)
  private val token = "A".repeat(43)

  @Test fun parsesAppKey() {
    val key = PairingKey.parse("dsh1.$instanceId.$token")
    assertEquals(instanceId, key.instanceId)
    assertEquals(token, key.token)
  }

  @Test fun rejectsWrongInstanceIdLength() {
    assertThrows(IllegalArgumentException::class.java) { PairingKey.parse("dsh1.${"a".repeat(63)}.$token") }
  }

  @Test fun rejectsBadTokenChars() {
    assertThrows(IllegalArgumentException::class.java) {
      PairingKey.parse("dsh1.$instanceId.${"A".repeat(42)}+")
    }
  }

  @Test fun rejectsMissingPrefix() {
    assertThrows(IllegalArgumentException::class.java) { PairingKey.parse("$instanceId.$token") }
  }
}
