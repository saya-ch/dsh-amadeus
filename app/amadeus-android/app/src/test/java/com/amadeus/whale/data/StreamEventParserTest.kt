package com.amadeus.whale.data

import com.amadeus.whale.domain.model.AmadeusSprite
import com.amadeus.whale.domain.model.StreamEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamEventParserTest {
  @Test
  fun `parse dialogue frame`() {
    val ev = StreamEventParser.parse(
      """{"type":"dialogue","text":"你好呀","tag":{"sprite":"happy"}}""",
    )
    assertTrue(ev is StreamEvent.DialogueEvent)
    val d = (ev as StreamEvent.DialogueEvent).dialogue
    assertEquals("你好呀", d.text)
    assertEquals(AmadeusSprite.happy, d.tag.sprite)
  }

  @Test
  fun `parse activity frame`() {
    val ev = StreamEventParser.parse(
      """{"type":"activity","kind":"tool","title":"调用工具 foo","detail":"..."}""",
    )
    assertTrue(ev is StreamEvent.ActivityEvent)
    val a = (ev as StreamEvent.ActivityEvent).activity
    assertEquals("tool", a.kind)
    assertEquals("调用工具 foo", a.title)
  }

  @Test
  fun `parse choice frame`() {
    val ev = StreamEventParser.parse(
      """{"type":"choice","choiceId":"c1","question":"选哪个?","options":[{"label":"A"},{"label":"B"}]}""",
    )
    assertTrue(ev is StreamEvent.ChoiceEvent)
    val c = (ev as StreamEvent.ChoiceEvent).choice
    assertEquals("c1", c.choiceId)
    assertEquals(2, c.options.size)
    assertEquals("A", c.options[0].label)
  }

  @Test
  fun `parse report frame`() {
    val ev = StreamEventParser.parse(
      """{"type":"report","reportId":"amw-abc123","title":"长文本内容","body":"第一句。第二句。"}""",
    )
    assertTrue(ev is StreamEvent.ReportEvent)
    val r = (ev as StreamEvent.ReportEvent).report
    assertEquals("amw-abc123", r.reportId)
    assertEquals("长文本内容", r.title)
    assertEquals("第一句。第二句。", r.body)
  }

  @Test
  fun `parse ended frame`() {
    val ev = StreamEventParser.parse("""{"type":"ended","reason":"done"}""")
    assertEquals(StreamEvent.Ended("done"), ev)
  }

  @Test
  fun `unknown frame returns null`() {
    assertEquals(null, StreamEventParser.parse("""{"type":"other"}"""))
  }
}
