package com.amadeus.whale.theatre

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AmbientSoundTest {
  @Test fun controllerDefaultsOn() { AmbientSoundController.enabled = true }
  @Test fun controllerCanToggle() {
    AmbientSoundController.enabled = true
    AmbientSoundController.enabled = false
    assertFalse(AmbientSoundController.enabled)
    AmbientSoundController.enabled = true
    assertTrue(AmbientSoundController.enabled)
  }
}