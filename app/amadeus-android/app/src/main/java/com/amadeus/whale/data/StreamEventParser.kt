package com.amadeus.whale.data

import com.amadeus.whale.domain.model.Activity
import com.amadeus.whale.domain.model.AmadeusMood
import com.amadeus.whale.domain.model.AmadeusSprite
import com.amadeus.whale.domain.model.AmadeusTag
import com.amadeus.whale.domain.model.AmadeusVoice
import com.amadeus.whale.domain.model.AmadeusWindow
import com.amadeus.whale.domain.model.ApprovalRequest
import com.amadeus.whale.domain.model.Choice
import com.amadeus.whale.domain.model.ChoiceOption
import com.amadeus.whale.domain.model.Dialogue
import com.amadeus.whale.domain.model.StreamEvent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** SSE 帧解析（架构 3.19 契约：dialogue/activity/choice/ended）。 */
object StreamEventParser {
  private val json = Json { ignoreUnknownKeys = true }

  fun parse(data: String): StreamEvent? {
    val obj = runCatching { json.parseToJsonElement(data) as JsonObject }.getOrNull() ?: return null
    return when ((obj["type"] as? JsonPrimitive)?.content) {
      "dialogue" -> {
        val text = (obj["text"] as? JsonPrimitive)?.content ?: return null
        val tag = parseTag(obj["tag"] as? JsonObject)
        StreamEvent.DialogueEvent(Dialogue(text, tag))
      }
      "activity" -> {
        val kind = (obj["kind"] as? JsonPrimitive)?.content ?: return null
        val title = (obj["title"] as? JsonPrimitive)?.content ?: ""
        val detail = (obj["detail"] as? JsonPrimitive)?.content ?: ""
        StreamEvent.ActivityEvent(Activity(kind, title, detail))
      }
      "choice" -> {
        val choiceId = (obj["choiceId"] as? JsonPrimitive)?.content ?: return null
        val question = (obj["question"] as? JsonPrimitive)?.content ?: ""
        val options = (obj["options"] as? JsonArray)?.mapNotNull { el ->
          val o = el as? JsonObject ?: return@mapNotNull null
          ChoiceOption(
            (o["label"] as? JsonPrimitive)?.contentOrNull ?: "",
            (o["description"] as? JsonPrimitive)?.contentOrNull,
          )
        } ?: emptyList()
        StreamEvent.ChoiceEvent(Choice(choiceId, question, options))
      }
      "approval" -> {
        val approvalId = (obj["approvalId"] as? JsonPrimitive)?.content ?: return null
        val toolName = (obj["toolName"] as? JsonPrimitive)?.content ?: ""
        val reason = (obj["reason"] as? JsonPrimitive)?.contentOrNull
        StreamEvent.ApprovalEvent(ApprovalRequest(approvalId, toolName, reason))
      }
      "ended" -> StreamEvent.Ended((obj["reason"] as? JsonPrimitive)?.content ?: "")
      else -> null
    }
  }

  private fun parseTag(o: JsonObject?): AmadeusTag {
    if (o == null) return AmadeusTag()
    fun str(k: String) = (o[k] as? JsonPrimitive)?.contentOrNull ?: ""
    fun enumOf(name: String, values: Array<out Enum<*>>): Int = values.indexOfFirst { it.name == name }
    val mood = enumOf(str("mood"), AmadeusMood.entries.toTypedArray())
      .takeIf { it >= 0 }?.let { AmadeusMood.entries[it] } ?: AmadeusMood.idle
    val sprite = enumOf(str("sprite"), AmadeusSprite.entries.toTypedArray())
      .takeIf { it >= 0 }?.let { AmadeusSprite.entries[it] } ?: AmadeusSprite.smile
    val voice = enumOf(str("voice"), AmadeusVoice.entries.toTypedArray())
      .takeIf { it >= 0 }?.let { AmadeusVoice.entries[it] } ?: AmadeusVoice.soft
    val window = enumOf(str("window"), AmadeusWindow.entries.toTypedArray())
      .takeIf { it >= 0 }?.let { AmadeusWindow.entries[it] } ?: AmadeusWindow.none
    return AmadeusTag(mood, sprite, voice, window, str("windowId"), str("windowTitle"))
  }
}
