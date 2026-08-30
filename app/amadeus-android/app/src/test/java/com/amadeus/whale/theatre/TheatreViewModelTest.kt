package com.amadeus.whale.theatre

import com.amadeus.whale.feed.DemoFeed
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TheatreViewModelTest {
  private fun vm() = TheatreViewModel(DemoFeed(), backgroundResolver = { "palace-night" })

  @Test fun loadSetsFirstSegment() = runTest {
    val v = vm()
    v.load()
    assertEquals("呜... 月光照在礁石上呢...", v.uiState.value.dialog)
  }

  @Test fun tapAdvancesOneAtATime() = runTest {
    val v = vm()
    v.load()
    val first = v.uiState.value.dialog
    v.onTap() // 第一次点击：完成打字
    assertEquals(first, v.uiState.value.dialog) // 句子不变，仅打字完成
    v.onTap() // 第二次点击：下一句
    assertTrue(v.uiState.value.dialog != first)
  }

  @Test fun skipToEndJumpsToLast() = runTest {
    val v = vm()
    v.load()
    v.skipToEnd()
    val segs = DemoFeed().initial()
    assertEquals(segs.last().dialog, v.uiState.value.dialog)
    assertFalse(v.hasNext())
  }
}