package com.amadeus.whale.theatre

import com.amadeus.whale.model.AmadeusSprite
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SpriteResolverTest {
  @Test fun assetMapCoversAllSprites() {
    for (sprite in AmadeusSprite.entries) {
      assertTrue(SpriteAssetMap.asset(sprite).startsWith("amadeus/"), "asset($sprite) 应落在 amadeus/")
    }
  }
  @Test fun shyMapsToWhaleShy() {
    assertEquals("amadeus/whale-shy.webp", SpriteAssetMap.asset(AmadeusSprite.shy))
  }
  @Test fun seriousMapsToWhaleSerious() {
    assertEquals("amadeus/whale-serious.webp", SpriteAssetMap.asset(AmadeusSprite.serious))
  }
}