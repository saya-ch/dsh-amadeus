package com.amadeus.whale.platform

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/** 触觉反馈（产品 1.11：只做发送消息时短震；架构 3.17）。 */
class Haptics(private val context: Context) {

  /** 发送消息时的短震反馈（反馈“消息已送出”）。 */
  fun sendVibration() {
    val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
      val manager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
      manager?.defaultVibrator
    } else {
      @Suppress("DEPRECATION")
      context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    } ?: return
    if (!vibrator.hasVibrator()) return
    try {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        vibrator.vibrate(VibrationEffect.createOneShot(30, VibrationEffect.DEFAULT_AMPLITUDE))
      } else {
        @Suppress("DEPRECATION")
        vibrator.vibrate(30)
      }
    } catch (_: Exception) {
      // 无振动权限/被系统禁用时静默忽略
    }
  }
}
