package com.amadeus.whale.feed

import com.amadeus.whale.model.AmadeusMood
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DemoFeedTest {
  @Test fun demoHas20To30Segments() = runTest {
    val segs = DemoFeed().initial()
    assertTrue(segs.size in 20..30, "demo 应为 20-30 句，实际 ${segs.size}")
  }

  @Test fun demoOpensWithMoonlitIntro() = runTest {
    val segs = DemoFeed().initial()
    assertTrue(segs.first().dialog.contains("月光"))
  }

  @Test fun demoHasVariedMoods() = runTest {
    val moods = DemoFeed().initial().map { it.tag.mood }.toSet()
    assertTrue(moods.size >= 3, "demo 应穿插多种心情，实际 $moods")
  }

  @Test fun demoEndsOnWarmNote() = runTest {
    val segs = DemoFeed().initial()
    assertTrue(segs.last().dialog.contains("家") || segs.last().dialog.contains("再见") || segs.last().dialog.contains("明天"))
  }
}