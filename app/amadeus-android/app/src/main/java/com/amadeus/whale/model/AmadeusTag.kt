package com.amadeus.whale.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

enum class AmadeusMood { shy, think, tool, happy, sad, idle }
enum class AmadeusSprite { shy, think, tool, serious, wag, gray, smile, talk }
enum class AmadeusWindow { none, report, preview, choice }

@Serializable
data class ChoiceOption(val label: String, val description: String? = null)

data class AmadeusTag(
  val mood: AmadeusMood = AmadeusMood.idle,
  val sprite: AmadeusSprite = AmadeusSprite.smile,
  val voice: String = "soft",
  val sfx: String = "none",
  val bgm: String = "none",
  val window: AmadeusWindow = AmadeusWindow.none,
  val windowId: String = "",
  val windowTitle: String = "",
  val choiceId: String = "",
  val options: List<ChoiceOption> = emptyList(),
)

object AmadeusTagParser {
  private val json = Json { ignoreUnknownKeys = true }

  fun parse(jsonText: String): AmadeusTag {
    val obj = runCatching { json.parseToJsonElement(jsonText) as JsonObject }.getOrNull() ?: return AmadeusTag()
    fun str(key: String): String = (obj[key] as? JsonPrimitive)?.contentOrNull ?: ""
    fun enumValueOf(name: String, enum: Array<out Enum<*>>): Int? =
      enum.indexOfFirst { it.name == name }.takeIf { it >= 0 }
    val mood = enumValueOf(str("mood"), AmadeusMood.entries.toTypedArray())
      ?.let { AmadeusMood.entries[it] } ?: AmadeusMood.idle
    val sprite = enumValueOf(str("sprite"), AmadeusSprite.entries.toTypedArray())
      ?.let { AmadeusSprite.entries[it] } ?: AmadeusSprite.smile
    val window = enumValueOf(str("window"), AmadeusWindow.entries.toTypedArray())
      ?.let { AmadeusWindow.entries[it] } ?: AmadeusWindow.none
    val options = (obj["options"] as? JsonArray)?.mapNotNull { el ->
      val o = el as? JsonObject ?: return@mapNotNull null
      ChoiceOption((o["label"] as? JsonPrimitive)?.contentOrNull ?: "", (o["description"] as? JsonPrimitive)?.contentOrNull)
    } ?: emptyList()
    return AmadeusTag(
      mood = mood, sprite = sprite,
      voice = str("voice").ifEmpty { "soft" },
      sfx = str("sfx").ifEmpty { "none" },
      bgm = str("bgm").ifEmpty { "none" },
      window = window,
      windowId = str("windowId"), windowTitle = str("windowTitle"),
      choiceId = str("choiceId"), options = options,
    )
  }
}