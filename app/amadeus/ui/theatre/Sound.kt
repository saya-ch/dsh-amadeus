package com.amadeus.whale.ui.theatre

import android.content.Context
import android.media.SoundPool

/**
 * 音效占位：wave/bell/none 对应 SoundPool
 * BGM 用 ExoPlayer，sfx 用 SoundPool
 */
class AmadeusSound(private val context: Context) {
  private val pool = SoundPool.Builder().setMaxStreams(4).build()
  // private val waveId = pool.load(context, R.raw.sfx_wave, 1)
  fun play(sfx: String) {
    // if (sfx == "wave") pool.play(waveId, 1f, 1f, 0, 0, 1f)
  }
  fun release() { pool.release() }
}
