package com.amadeus.whale.domain

import com.amadeus.whale.domain.model.AmadeusTag
import com.amadeus.whale.domain.model.Dialogue
import com.amadeus.whale.domain.model.StreamEvent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionLogTest {
  @Test
  fun `append dialogue and activity, filters work`() {
    val log = SessionLog()
    log.append(StreamEvent.DialogueEvent(Dialogue("你好", AmadeusTag())))
    log.append(StreamEvent.ActivityEvent(com.amadeus.whale.domain.model.Activity("tool", "调用工具", "详情")))
    log.append(StreamEvent.DialogueEvent(Dialogue("再见", AmadeusTag())))

    assertEquals(3, log.allEntries().size)
    assertEquals(2, log.dialogueEntries().size)
  }

  @Test
  fun `ended is not a log entry`() {
    val log = SessionLog()
    log.append(StreamEvent.Ended("done"))
    assertTrue(log.allEntries().isEmpty())
  }
}

class SessionStateMachineTest {
  @Test
  fun `dialogue drives working state with typing`() {
    val machine = SessionStateMachine(SessionLog())
    machine.onEvent(StreamEvent.DialogueEvent(Dialogue("hi", AmadeusTag())))
    assertEquals(SessionPhase.WORKING, machine.state.value.phase)
    assertTrue(machine.state.value.typing)
    assertEquals("hi", machine.state.value.currentDialogue?.text)
  }

  @Test
  fun `choice drives waiting state`() {
    val machine = SessionStateMachine(SessionLog())
    machine.onEvent(StreamEvent.ChoiceEvent(com.amadeus.whale.domain.model.Choice("c1", "选?", listOf())))
    assertEquals(SessionPhase.WAITING_CHOICE, machine.state.value.phase)
    assertEquals("c1", machine.state.value.choice?.choiceId)
  }

  @Test
  fun `tap completes typing`() {
    val machine = SessionStateMachine(SessionLog())
    machine.onEvent(StreamEvent.DialogueEvent(Dialogue("hi", AmadeusTag())))
    assertTrue(machine.state.value.typing)
    machine.tapToComplete()
    assertFalse(machine.state.value.typing)
  }

  @Test
  fun `ended returns to idle`() {
    val machine = SessionStateMachine(SessionLog())
    machine.onEvent(StreamEvent.DialogueEvent(Dialogue("hi", AmadeusTag())))
    machine.onEvent(StreamEvent.Ended("done"))
    assertEquals(SessionPhase.IDLE, machine.state.value.phase)
    assertFalse(machine.state.value.typing)
  }
}

class AppLaunchDeciderTest {
  private val prefs: suspend () -> DevicePrefs = { DevicePrefs() }
  private val restore: suspend (String) -> String? = { null }

  @Test
  fun `first run goes to demo`() = runTest {
    val d = AppLaunchDecider({ DevicePrefs(demoSeen = false) }, restore)
    assertEquals(LaunchTarget.FirstRunDemo, d.decide())
  }

  @Test
  fun `seen demo no gateway goes to connect daily`() = runTest {
    val d = AppLaunchDecider({ DevicePrefs(demoSeen = true, gatewayUrl = null) }, restore)
    assertEquals(LaunchTarget.ConnectDaily, d.decide())
  }

  @Test
  fun `seen demo with gateway and restore success goes to theatre`() = runTest {
    val d = AppLaunchDecider(
      { DevicePrefs(demoSeen = true, gatewayUrl = "https://192.168.0.104:3444") },
      { "session-1" },
    )
    assertEquals(LaunchTarget.RealTheatre("session-1"), d.decide())
  }

  @Test
  fun `seen demo with gateway but restore fails goes to connect`() = runTest {
    val d = AppLaunchDecider(
      { DevicePrefs(demoSeen = true, gatewayUrl = "https://192.168.0.104:3444") },
      { null },
    )
    assertEquals(LaunchTarget.ConnectAfterFailure, d.decide())
  }
}

private typealias DevicePrefs = com.amadeus.whale.data.store.DevicePrefs
