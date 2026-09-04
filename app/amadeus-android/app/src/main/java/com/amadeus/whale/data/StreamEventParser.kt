package com.amadeus.whale.data

import com.amadeus.whale.domain.model.Activity
import com.amadeus.whale.domain.model.AmadeusSprite
import com.amadeus.whale.domain.model.AmadeusTag
import com.amadeus.whale.domain.model.AmadeusVoice
import com.amadeus.whale.domain.model.AmadeusWindow
import com.amadeus.whale.domain.model.ApprovalRequest
import com.amadeus.whale.domain.model.Choice
import com.amadeus.whale.domain.model.ChoiceOption
import com.amadeus.whale.domain.model.Dialogue
import com.amadeus.whale.domain.model.Report
import com.amadeus.whale.domain.model.StreamEvent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** SSE 帧解析（架构 3.19 契约：dialogue/activity/choice/approval/report/ended）。 */
object StreamEventParser {
  private val json = Json { ignoreUnknownKeys = true }

  fun parse(data: String): StreamEvent? {
    val obj = runCatching { json.parseToJsonElement(data) as JsonObject }.getOrNull() ?: return null
    return when ((obj["type"] as? JsonPrimitive)?.content) {
      "dialogue" -> {
        val text = (obj["text"] as? JsonPrimitive)?.content ?: return null
        val tag = parseTag(obj["tag"] as? JsonObject)
        val working = (obj["working"] as? JsonPrimitive)?.content == "true"
        StreamEvent.DialogueEvent(Dialogue(text, tag, working))
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
      "report" -> {
        val reportId = (obj["reportId"] as? JsonPrimitive)?.content ?: return null
        val title = (obj["title"] as? JsonPrimitive)?.content ?: ""
        val body = (obj["body"] as? JsonPrimitive)?.content ?: ""
        StreamEvent.ReportEvent(Report(reportId, title, body))
      }
      "ended" -> StreamEvent.Ended((obj["reason"] as? JsonPrimitive)?.content ?: "")
      "turn" -> StreamEvent.TurnEnded((obj["reason"] as? JsonPrimitive)?.content ?: "")
      else -> null
    }
  }

  private fun parseTag(o: JsonObject?): AmadeusTag {
    if (o == null) return AmadeusTag()
    fun str(k: String) = (o[k] as? JsonPrimitive)?.contentOrNull ?: ""
    val sprite = runCatching { AmadeusSprite.valueOf(str("sprite").ifEmpty { "normal" }) }
      .getOrDefault(AmadeusSprite.normal)
    val voice = runCatching { AmadeusVoice.valueOf(str("voice").ifEmpty { "soft" }) }.getOrDefault(AmadeusVoice.soft)
    val window = runCatching { AmadeusWindow.valueOf(str("window").ifEmpty { "none" }) }.getOrDefault(AmadeusWindow.none)
    return AmadeusTag(sprite, voice, window, str("windowId"), str("windowTitle"))
  }
}
