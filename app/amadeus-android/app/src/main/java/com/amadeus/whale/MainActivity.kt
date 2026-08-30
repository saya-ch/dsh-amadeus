package com.amadeus.whale

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import com.amadeus.whale.ui.saveslot.SaveSlot
import com.amadeus.whale.ui.saveslot.SaveSlotRepository
import com.amadeus.whale.ui.saveslot.SaveSlotScreen
import com.amadeus.whale.ui.saveslot.SaveSlotViewModel
import com.amadeus.whale.ui.theatre.TheatreScreen
import com.amadeus.whale.ui.theatre.TheatreViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers

/**
 * Amadeus Whale — independent Galgame APP.
 * 选档 -> 剧场（点一下下一句 + 选项卡 + 报告/预览窗口）
 */
class MainActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContent {
      MaterialTheme {
        var currentSlot by remember { mutableStateOf<String?>(null) }
        if (currentSlot == null) {
          val vm = remember {
            SaveSlotViewModel(
              repo = object : SaveSlotRepository {
                override suspend fun list(mode: String) = emptyList<SaveSlot>()
                override suspend fun create(mode: String) = SaveSlot(
                  "demo_${System.currentTimeMillis()}",
                  "Amadeus 存档 Demo",
                  System.currentTimeMillis(),
                  "呜... 第一次在月夜的礁石边遇见你...",
                )
              },
              scope = CoroutineScope(Dispatchers.Main),
            )
          }
          LaunchedEffect(Unit) { vm.load() }
          SaveSlotScreen(
            viewModel = vm,
            onSlotClick = { currentSlot = it },
            onNewSlot = { vm.createNew { currentSlot = it } },
          )
        } else {
          val vm = remember { TheatreViewModel() }
          LaunchedEffect(currentSlot) {
            vm.onMessage(
              "呜... 月光照在礁石上呢...\n" +
                "[[AMW:{\"mood\":\"shy\",\"sprite\":\"shy\",\"voice\":\"whisper\",\"sfx\":\"wave\",\"bgm\":\"rain\"}]]\n" +
                "有你在身边，感觉暖暖的 啾~\n" +
                "[[AMW:{\"mood\":\"happy\",\"sprite\":\"wag\",\"voice\":\"soft\",\"sfx\":\"none\",\"bgm\":\"none\"}]]\n" +
                "要不要一起去看看刚才的报告呀？\n" +
                "[[AMW:{\"mood\":\"idle\",\"sprite\":\"smile\",\"voice\":\"soft\",\"sfx\":\"bell\",\"bgm\":\"none\",\"window\":\"report\",\"windowId\":\"rpt_demo\",\"windowTitle\":\"今日小报告\"}]]",
            )
          }
          TheatreScreen(viewModel = vm)
        }
      }
    }
  }
}
