package com.amadeus.whale.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Amadeus 自定义语义色板 —— galgame 专属件（名字牌/对话框/读档卡片等）用，
 * 标准组件走 Material3 colorScheme。与 MaterialTheme 双通道注入（架构 3.2）。
 */
@Immutable
data class AmadeusColors(
  // 剧场
  val dialogueBox: Color = Color(0xCC2A2118),   // 对话框半透明深棕（暖调）
  val dialogueBorder: Color = Color(0x66D9B38C), // 对话框描边（暖金）
  val namePlateBg: Color = Color(0xFF8C5B3A),   // 名字牌底色（暖棕）
  val namePlateText: Color = Color(0xFFFFF5E6), // 名字牌文字（暖白）
  val dialogueText: Color = Color(0xFFFFF5E6),  // 对话框正文（暖白）
  val breathingArrow: Color = Color(0xAAFFF5E6),// 呼吸▼
  // 页面
  val screenBackground: Color = Color(0xFFF7EFE4), // 暖米白底
  val cardBackground: Color = Color(0xFFFFFDF8),   // 卡片暖白
  val cardBorder: Color = Color(0x33B08968),       // 卡片描边
  val primaryText: Color = Color(0xFF3D2E22),      // 主文字（深棕）
  val secondaryText: Color = Color(0xFF8A7766),    // 次文字（灰棕）
  val accent: Color = Color(0xFFB0764A),           // 强调（暖橙棕）
  val accentSoft: Color = Color(0x33B0764A),       // 强调弱（用于选中底色）
  // 覆盖层
  val overlayScrim: Color = Color(0x66000000),     // 覆盖层遮罩
  val sheetBackground: Color = Color(0xF2FFFDF8),  // 侧栏/窗口背景（暖白高透明）
  // 状态
  val success: Color = Color(0xFF6B8E4E),          // 成功（暖绿）
  val danger: Color = Color(0xFFB0523A),           // 危险（暖红）
)

/** 默认主题：暖色治愈（产品 1.14）。 */
val WarmHealingAmadeusColors = AmadeusColors()

/** 未来可扩充的主题（深色/冷调等），本期先占位。 */
val DarkAmadeusColors = AmadeusColors(
  dialogueBox = Color(0xCC1A1A1E),
  dialogueBorder = Color(0x444A4A52),
  namePlateBg = Color(0xFF3A3A42),
  namePlateText = Color(0xFFE8E8EC),
  dialogueText = Color(0xFFE8E8EC),
  breathingArrow = Color(0xAAE8E8EC),
  screenBackground = Color(0xFF17171B),
  cardBackground = Color(0xFF222228),
  cardBorder = Color(0x335E5E68),
  primaryText = Color(0xFFE8E8EC),
  secondaryText = Color(0xFF9A9AA4),
  accent = Color(0xFF7C9BD1),
  accentSoft = Color(0x337C9BD1),
  overlayScrim = Color(0x99000000),
  sheetBackground = Color(0xF227272E),
  success = Color(0xFF6BA368),
  danger = Color(0xFFC2605A),
)

/** 主题标识（设置里可选）。 */
enum class AmadeusThemeId { WARM_HEALING, DARK }

val LocalAmadeusColors = staticCompositionLocalOf { WarmHealingAmadeusColors }
