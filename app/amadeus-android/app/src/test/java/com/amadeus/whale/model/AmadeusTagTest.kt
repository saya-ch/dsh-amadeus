package com.amadeus.whale.model

import kotlin.test.Test
import kotlin.test.assertEquals

class AmadeusTagTest {
  @Test fun parseFullTag() {
    val tag = AmadeusTagParser.parse(
      """{"mood":"happy","sprite":"wag","voice":"soft","sfx":"bell","bgm":"none","window":"report","windowId":"rpt_123","windowTitle":"今日小报告"}"""
    )
    assertEquals(AmadeusMood.happy, tag.mood)
    assertEquals(AmadeusSprite.wag, tag.sprite)
    assertEquals("rpt_123", tag.windowId)
    assertEquals(AmadeusWindow.report, tag.window)
  }

  @Test fun parseChoiceOptions() {
    val tag = AmadeusTagParser.parse(
      """{"mood":"idle","window":"choice","choiceId":"c1","options":[{"label":"继续","description":"干活"},{"label":"停下"}]}"""
    )
    assertEquals(AmadeusWindow.choice, tag.window)
    assertEquals(2, tag.options.size)
    assertEquals("继续", tag.options[0].label)
    assertEquals("干活", tag.options[0].description)
  }

  @Test fun parsePartialDefaults() {
    val tag = AmadeusTagParser.parse("""{"mood":"shy"}""")
    assertEquals(AmadeusMood.shy, tag.mood)
    assertEquals(AmadeusSprite.smile, tag.sprite)
    assertEquals(AmadeusWindow.none, tag.window)
    assertEquals("", tag.windowId)
    assertEquals(emptyList(), tag.options)
  }
}