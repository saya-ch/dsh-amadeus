package com.amadeus.whale.model

data class AmadeusSegment(
  val dialog: String,
  val tag: AmadeusTag,
  val windowId: String? = null,
)

object AmadeusSegmentParser {
  private val tagRegex = Regex("""\[\[AMW:\s*(\{[\s\S]*?\})\s*\]\]""")

  fun parse(raw: String): List<AmadeusSegment> {
    val segments = mutableListOf<AmadeusSegment>()
    var cursor = 0
    var match = tagRegex.find(raw, cursor)
    while (match != null) {
      val clean = raw.substring(cursor, match.range.first).trim()
      val tag = AmadeusTagParser.parse(match.groupValues[1])
      if (clean.isNotEmpty()) segments.add(AmadeusSegment(clean, tag, tag.windowId.ifEmpty { null }))
      cursor = match.range.last + 1
      while (cursor < raw.length && (raw[cursor] == '\n' || raw[cursor] == '\r')) cursor++
      match = tagRegex.find(raw, cursor)
    }
    val tail = raw.substring(cursor).trim()
    if (tail.isNotEmpty()) segments.add(AmadeusSegment(tail, AmadeusTag()))
    if (segments.isEmpty() && raw.trim().isNotEmpty()) segments.add(AmadeusSegment(raw.trim(), AmadeusTag()))
    return segments
  }

  fun ensureAmadeusTag(raw: String): String {
    if (tagRegex.containsMatchIn(raw)) return raw
    return raw.trimEnd('\n', '\r') + "\n[[AMW:{\"mood\":\"idle\",\"sprite\":\"smile\"}]]"
  }
}

fun ensureAmadeusTag(raw: String): String = AmadeusSegmentParser.ensureAmadeusTag(raw)