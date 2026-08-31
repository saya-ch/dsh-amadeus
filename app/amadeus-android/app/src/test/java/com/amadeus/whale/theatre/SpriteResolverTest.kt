package com.amadeus.whale.theatre

import com.amadeus.whale.model.AmadeusSprite
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SpriteResolverTest {
  @Test fun assetMapCoversAllSprites() {
    for (sprite in AmadeusSprite.entries) {
      assertTrue(
        SpriteAssetMap.asset(sprite).startsWith("file:///android_asset/amadeus/"),
        "asset($sprite) 应落在 file:///android_asset/amadeus/",
      )
    }
  }
  @Test fun shyMapsToWhaleShy() {
    assertEquals("file:///android_asset/amadeus/whale-shy.webp", SpriteAssetMap.asset(AmadeusSprite.shy))
  }
  @Test fun seriousMapsToWhaleSerious() {
    assertEquals("file:///android_asset/amadeus/whale-serious.webp", SpriteAssetMap.asset(AmadeusSprite.serious))
  }
}