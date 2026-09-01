package com.amadeus.whale.domain

import com.amadeus.whale.domain.model.AmadeusMood
import com.amadeus.whale.domain.model.AmadeusSprite
import com.amadeus.whale.domain.model.AmadeusTag
import com.amadeus.whale.domain.model.Dialogue

/**
 * demo 剧本（产品 1.12：相识，月夜初遇）。
 * 固定脚本，一次进 App 播完；之后设置里可重放（产品 1.10）。
 */
object DemoFeed {
  fun initial(): List<Dialogue> = listOf(
    Dialogue("（海浪声）……", AmadeusTag(AmadeusMood.idle, AmadeusSprite.smile)),
    Dialogue("呜……你也睡不着吗？", AmadeusTag(AmadeusMood.shy, AmadeusSprite.shy)),
    Dialogue("我叫鲸鱼娘……今晚的月亮，好漂亮。", AmadeusTag(AmadeusMood.idle, AmadeusSprite.smile)),
    Dialogue("你也是一个人在海边散步吗？", AmadeusTag(AmadeusMood.shy, AmadeusSprite.shy)),
    Dialogue("……要不要，听我讲讲海里的故事？", AmadeusTag(AmadeusMood.happy, AmadeusSprite.wag)),
    Dialogue("（月光洒在海面上，波光粼粼）", AmadeusTag(AmadeusMood.idle, AmadeusSprite.smile)),
    Dialogue("其实呀，我不是普通的鲸鱼……", AmadeusTag(AmadeusMood.shy, AmadeusSprite.think)),
    Dialogue("我来自很深很深的海底，那里有会发光的鱼，还有沉船里的宝藏。", AmadeusTag(AmadeusMood.happy, AmadeusSprite.wag)),
    Dialogue("呜……说太多了，你会觉得我奇怪吗？", AmadeusTag(AmadeusMood.shy, AmadeusSprite.shy)),
    Dialogue("那……今晚就到这里吧。", AmadeusTag(AmadeusMood.idle, AmadeusSprite.smile)),
    Dialogue("明天，我还会在这里等你。", AmadeusTag(AmadeusMood.happy, AmadeusSprite.wag)),
    Dialogue("（鲸鱼娘的身影，在月光下渐渐模糊……）", AmadeusTag(AmadeusMood.idle, AmadeusSprite.smile)),
  )
}
