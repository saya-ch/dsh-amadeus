package com.amadeus.whale.theatre

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.amadeus.whale.data.store.DevicePrefsStore
import com.amadeus.whale.theme.AmadeusThemeId
import com.amadeus.whale.theme.LocalAmadeusColors
import kotlinx.coroutines.launch

/** 背景库（产品 1.7：用户可选；素材就位后可扩充）。 */
private data class BackgroundEntry(val name: String, val label: String)

private val BACKGROUND_LIBRARY = listOf(
  BackgroundEntry("bg-claude-writing-study", "居家书桌"),
  BackgroundEntry("bg-deepseek-seaside-study", "海边学习"),
  BackgroundEntry("bg-gpt-collaboration-workshop", "协作工坊"),
  BackgroundEntry("palace-night", "月夜宫殿"),
)

/** 设置分页（架构 3.14）。 */
private enum class SettingsTab { PERFORMANCE, CONNECTION }

/**
 * 设置覆盖层（架构 3.14 / 产品 1.10）：分页——演出（文字速度/主题/背景/demo 重放）+ 连接（网关/断开）。
 * 读 DataStore 偏好，改即写 → 响应式立即生效（3.14）。
 */
@Composable
fun SettingsOverlay(
  prefsStore: DevicePrefsStore,
  onClose: () -> Unit,
  onReplayDemo: () -> Unit,
  onDisconnect: () -> Unit,
  onReconnect: () -> Unit,
  gatewayUrl: String?,
) {
  val prefs by prefsStore.flow.collectAsState(initial = com.amadeus.whale.data.store.DevicePrefs())
  val colors = LocalAmadeusColors.current
  val scope = rememberCoroutineScope()
  var tab by androidx.compose.runtime.remember { mutableStateOf(SettingsTab.PERFORMANCE) }

  Column(
    modifier = Modifier
      .fillMaxWidth()
      .fillMaxHeight(0.62f)
      .background(colors.sheetBackground, RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)),
  ) {
    // 顶部深蓝横幅（maid-atelier settings-frame，CC BY-NC-SA 4.0）
    Box(
      modifier = Modifier
        .fillMaxWidth()
        .height(64.dp)
        .clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)),
    ) {
      com.amadeus.whale.theatre.AssetImage(
        name = "maid-settings-frame-v1",
        modifier = Modifier.fillMaxSize(),
        contentScale = androidx.compose.ui.layout.ContentScale.Crop,
      )
      Text(
        text = "设置",
        style = MaterialTheme.typography.headlineSmall,
        color = colors.namePlateText,
        modifier = Modifier.align(Alignment.Center).padding(start = 8.dp),
      )
      Text(
        text = "✕",
        color = colors.namePlateText,
        fontSize = 20.sp,
        modifier = Modifier.align(Alignment.CenterEnd).padding(end = 20.dp).clickable(onClick = onClose),
      )
    }
    Spacer(Modifier.height(16.dp))
    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
      // 分页 Tab
      Row {
        TabItem("演出", tab == SettingsTab.PERFORMANCE) { tab = SettingsTab.PERFORMANCE }
        TabItem("连接", tab == SettingsTab.CONNECTION) { tab = SettingsTab.CONNECTION }
      }
      HorizontalDivider(color = colors.cardBorder, thickness = 1.dp)
      Spacer(Modifier.height(12.dp))

      when (tab) {
        SettingsTab.PERFORMANCE -> PerformanceTab(prefsStore, prefs, colors, scope)
        SettingsTab.CONNECTION -> ConnectionTab(
          gatewayUrl = gatewayUrl,
          onDisconnect = onDisconnect,
          onReconnect = onReconnect,
          colors = colors,
        )
      }
      Spacer(Modifier.weight(1f))

      // 底部：demo 重放
      OutlinedButton(onClick = onReplayDemo, modifier = Modifier.fillMaxWidth()) {
        Text("重放 demo")
      }
    }
  }
}

@Composable
private fun TabItem(label: String, selected: Boolean, onClick: () -> Unit) {
  val colors = LocalAmadeusColors.current
  Text(
    text = label,
    color = if (selected) colors.accent else colors.secondaryText,
    fontSize = 15.sp,
    modifier = Modifier.padding(end = 20.dp, bottom = 6.dp).clickable(onClick = onClick),
  )
}

@Composable
private fun PerformanceTab(
  prefsStore: DevicePrefsStore,
  prefs: com.amadeus.whale.data.store.DevicePrefs,
  colors: com.amadeus.whale.theme.AmadeusColors,
  scope: kotlinx.coroutines.CoroutineScope,
) {
  Column {
    // 文字速度（产品 1.13：三档）
    Text("文字速度", color = colors.primaryText, fontSize = 14.sp)
    Row {
      SpeedChip("慢", prefs.textSpeed == 0) { scope.launch { prefsStore.setTextSpeed(0) } }
      SpeedChip("中", prefs.textSpeed == 1) { scope.launch { prefsStore.setTextSpeed(1) } }
      SpeedChip("快", prefs.textSpeed == 2) { scope.launch { prefsStore.setTextSpeed(2) } }
    }
    Spacer(Modifier.height(12.dp))

    // 主题（产品 1.14）
    Text("主题", color = colors.primaryText, fontSize = 14.sp)
    Row {
      ThemeChip("暖色治愈", prefs.themeId == AmadeusThemeId.WARM_HEALING) {
        scope.launch { prefsStore.setThemeId(AmadeusThemeId.WARM_HEALING) }
      }
      ThemeChip("深色", prefs.themeId == AmadeusThemeId.DARK) {
        scope.launch { prefsStore.setThemeId(AmadeusThemeId.DARK) }
      }
    }
    Spacer(Modifier.height(12.dp))

    // 背景选择（产品 1.7/1.10：用户可选背景库）
    Text("背景", color = colors.primaryText, fontSize = 14.sp)
    LazyRow {
      items(BACKGROUND_LIBRARY) { bg ->
        val label = bg.label
        ThemeChip(label, prefs.background == bg.name) {
          scope.launch { prefsStore.setBackground(bg.name) }
        }
      }
    }
    Spacer(Modifier.height(12.dp))

    // BGM（产品 1.11：用户可选/关闭/音量；本期预留接口不播放）
    Row(verticalAlignment = Alignment.CenterVertically) {
      Text("背景音乐", color = colors.primaryText, fontSize = 14.sp, modifier = Modifier.weight(1f))
      Switch(
        checked = prefs.bgmEnabled,
        onCheckedChange = { scope.launch { prefsStore.setBgmEnabled(it) } },
      )
    }
    if (prefs.bgmEnabled) {
      Text(
        text = "音量 ${prefs.bgmVolume}",
        color = colors.secondaryText,
        fontSize = 12.sp,
      )
      Slider(
        value = prefs.bgmVolume.toFloat(),
        onValueChange = { scope.launch { prefsStore.setBgmVolume(it.toInt()) } },
        valueRange = 0f..100f,
      )
    }
    Spacer(Modifier.height(12.dp))

    // 工具进度（产品 1.13）
    Row(verticalAlignment = Alignment.CenterVertically) {
      Text("显示工具进度", color = colors.primaryText, fontSize = 14.sp, modifier = Modifier.weight(1f))
      Switch(
        checked = prefs.toolProgress,
        onCheckedChange = { scope.launch { prefsStore.setToolProgress(it) } },
      )
    }
    Spacer(Modifier.height(8.dp))

    // 触觉（产品 1.11）
    Row(verticalAlignment = Alignment.CenterVertically) {
      Text("发送消息触觉反馈", color = colors.primaryText, fontSize = 14.sp, modifier = Modifier.weight(1f))
      Switch(
        checked = prefs.hapticsEnabled,
        onCheckedChange = { scope.launch { prefsStore.setHapticsEnabled(it) } },
      )
    }
  }
}

@Composable
private fun ConnectionTab(
  gatewayUrl: String?,
  onDisconnect: () -> Unit,
  onReconnect: () -> Unit,
  colors: com.amadeus.whale.theme.AmadeusColors,
) {
  Column {
    Text("当前网关", color = colors.primaryText, fontSize = 14.sp)
    Spacer(Modifier.height(4.dp))
    Text(
      text = gatewayUrl ?: "未连接",
      color = if (gatewayUrl != null) colors.accent else colors.secondaryText,
      fontSize = 13.sp,
    )
    Spacer(Modifier.height(16.dp))
    OutlinedButton(onClick = onReconnect, modifier = Modifier.fillMaxWidth()) {
      Text("重新配对")
    }
    Spacer(Modifier.height(8.dp))
    Button(onClick = onDisconnect, modifier = Modifier.fillMaxWidth()) {
      Text("断开连接")
    }
    Spacer(Modifier.height(8.dp))
    Text(
      text = "断开后需重新扫码配对",
      color = colors.secondaryText,
      fontSize = 12.sp,
    )
  }
}

@Composable
private fun SpeedChip(label: String, selected: Boolean, onClick: () -> Unit) {
  val colors = LocalAmadeusColors.current
  Box(
    modifier = Modifier
      .padding(end = 8.dp)
      .background(
        if (selected) colors.accentSoft else colors.cardBackground,
        RoundedCornerShape(8.dp),
      )
      .clickable(onClick = onClick)
      .padding(horizontal = 12.dp, vertical = 6.dp),
  ) {
    Text(
      text = label,
      color = if (selected) colors.accent else colors.secondaryText,
      fontSize = 13.sp,
    )
  }
}

@Composable
private fun ThemeChip(label: String, selected: Boolean, onClick: () -> Unit) {
  val colors = LocalAmadeusColors.current
  Box(
    modifier = Modifier
      .padding(end = 8.dp)
      .background(
        if (selected) colors.accentSoft else colors.cardBackground,
        RoundedCornerShape(8.dp),
      )
      .clickable(onClick = onClick)
      .padding(horizontal = 12.dp, vertical = 6.dp),
  ) {
    Text(
      text = label,
      color = if (selected) colors.accent else colors.secondaryText,
      fontSize = 13.sp,
    )
  }
}
