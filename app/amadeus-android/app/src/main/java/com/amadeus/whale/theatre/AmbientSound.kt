package com.amadeus.whale.theatre

import android.content.Context
import android.media.MediaPlayer

object AmbientSoundController { var enabled: Boolean = true }

class AmbientSound(private val context: Context) {
  private val players = mutableMapOf<String, MediaPlayer?>()

  private fun rawId(name: String): Int =
    context.resources.getIdentifier(name, "raw", context.packageName)

  fun play(sfx: String) {
    if (!AmbientSoundController.enabled) return
    val res = when (sfx) {
      "wave" -> rawId("sfx_wave")
      "bell" -> rawId("sfx_bell")
      else -> 0
    }
    if (res == 0) return
    players[sfx]?.release()
    players[sfx] = MediaPlayer.create(context, res)?.apply { start() }
  }

  fun playBgm(bgm: String, looping: Boolean = true) {
    if (!AmbientSoundController.enabled) return
    if (bgm != "rain") return
    val res = rawId("bgm_rain")
    if (res == 0) return
    stopBgm()
    players["bgm"] = MediaPlayer.create(context, res)?.apply {
      isLooping = looping
      start()
    }
  }

  fun stopBgm() { players.remove("bgm")?.release() }
  fun stopAll() { players.values.forEach { it?.release() }; players.clear() }
}