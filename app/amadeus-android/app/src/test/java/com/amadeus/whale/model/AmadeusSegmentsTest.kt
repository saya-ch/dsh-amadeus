package com.amadeus.whale.model

import kotlin.test.Test
import kotlin.test.assertEquals

class AmadeusSegmentsTest {
  @Test fun splitWithTags() {
    val raw = "呜... 月光照在礁石上呢...\n" +
      "[[AMW:{\"mood\":\"shy\",\"sprite\":\"shy\"}]]\n" +
      "有你在身边，感觉暖暖的 啾~\n" +
      "[[AMW:{\"mood\":\"happy\",\"sprite\":\"wag\"}]]"
    val segs = AmadeusSegmentParser.parse(raw)
    assertEquals(2, segs.size)
    assertEquals("呜... 月光照在礁石上呢...", segs[0].dialog)
    assertEquals(AmadeusMood.shy, segs[0].tag.mood)
    assertEquals("有你在身边，感觉暖暖的 啾~", segs[1].dialog)
    assertEquals(AmadeusMood.happy, segs[1].tag.mood)
  }

  @Test fun segmentWithoutTagGetsDefault() {
    val segs = AmadeusSegmentParser.parse("只是一句话")
    assertEquals(1, segs.size)
    assertEquals("只是一句话", segs[0].dialog)
    assertEquals(AmadeusMood.idle, segs[0].tag.mood)
  }

  @Test fun ensureTagAppendsWhenMissing() {
    assertEquals("你好\n[[AMW:{\"mood\":\"idle\",\"sprite\":\"smile\"}]]", ensureAmadeusTag("你好"))
    assertEquals("你好\n[[AMW:{}]]", ensureAmadeusTag("你好\n[[AMW:{}]]"))
  }

  @Test fun segmentExposesWindowId() {
    val segs = AmadeusSegmentParser.parse("报告好了\n[[AMW:{\"window\":\"report\",\"windowId\":\"rpt_1\",\"windowTitle\":\"报告\"}]]")
    assertEquals("rpt_1", segs[0].windowId)
    assertEquals("报告", segs[0].tag.windowTitle)
  }
}