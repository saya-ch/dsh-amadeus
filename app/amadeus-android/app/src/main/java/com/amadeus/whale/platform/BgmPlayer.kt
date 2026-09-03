package com.amadeus.whale.platform

import android.media.MediaPlayer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.amadeus.whale.R

/**
 * BGM 播放器（产品 1.11：用户可选曲目/关闭/调音量）。
 * 最小实现：MediaPlayer 循环播 res/raw 曲目（当前唯一 bgm_rain 雨声，CC0 占位待换）。
 * 挂在 AppRoot 全 App 生效：bgmEnabled 关闭时暂停、bgmVolume 即时生效、composable 离开时释放。
 */
@Composable
fun BgmPlayerEffect(
  bgmEnabled: Boolean,
  bgmVolume: Int,
) {
  val context = LocalContext.current
  // player 生命周期跟 composable（remember 无 key），DisposableEffect 只处理启停/音量
  val player = remember {
    MediaPlayer.create(context, R.raw.bgm_rain)?.apply { isLooping = true }
  }

  DisposableEffect(player, bgmEnabled, bgmVolume) {
    if (player == null) return@DisposableEffect onDispose {}
    if (bgmEnabled) {
      player.setVolume(bgmVolume / 100f, bgmVolume / 100f)
      if (!player.isPlaying) player.start()
    } else if (player.isPlaying) {
      player.pause()
    }
    onDispose {
      // MediaPlayer 可能已被 release（另一 DisposableEffect dispose 顺序不定）：
      // isPlaying/pause 在释放后调用会抛 IllegalStateException，崩溃兜底。
      try {
        if (player.isPlaying) player.pause()
      } catch (_: IllegalStateException) {
      }
    }
  }

  DisposableEffect(player) {
    onDispose {
      try {
        player?.release()
      } catch (_: IllegalStateException) {
      }
    }
  }
}
