package com.amadeus.whale.screen

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.amadeus.whale.data.HttpAuthService
import com.amadeus.whale.domain.AuthService
import com.amadeus.whale.domain.PairResult
import com.amadeus.whale.scan.ScanActivity
import com.amadeus.whale.theme.LocalAmadeusColors
import kotlinx.coroutines.launch

/** 连接剧场 UI 状态。 */
private data class ConnUiState(
  val pairing: Boolean = false,
  val manualOpen: Boolean = false,
  val error: String? = null,
  val success: Boolean = false,
)

/**
 * 连接剧场（产品 1.12 / 架构 3.16）：鲸鱼娘等你接入。
 * - 扫码配对（ScanActivity，架构 3.5）
 * - 手动输入 origin
 * - 配对中演出状态（产品 1.12：她回来了）
 */
@Composable
fun ConnectionScreen(
  firstPairing: Boolean,
  authService: AuthService,
  onPaired: (sessionId: String?) -> Unit,
  onBackToDemo: () -> Unit,
) {
  val colors = LocalAmadeusColors.current
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  var ui by remember { mutableStateOf(ConnUiState()) }
  var manualInput by remember { mutableStateOf("") }

  // 扫码结果处理
  val scanLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
    androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult(),
  ) { result ->
    val raw = result.data?.getStringExtra("scan_result")
    if (raw != null) {
      ui = ui.copy(pairing = true, error = null)
      scope.launch {
        val outcome = authService.pair(raw)
        when (outcome) {
          is PairResult.Success -> {
            ui = ui.copy(pairing = false, success = true)
            onPaired(null)
          }
          is PairResult.Failure -> {
            // 产品 1.12：配对失败演出“她没找到你的电脑”
            ui = ui.copy(pairing = false, error = outcome.message)
          }
          PairResult.Cancelled -> ui = ui.copy(pairing = false)
        }
      }
    }
  }

  fun startPair(rawInput: String) {
    ui = ui.copy(pairing = true, error = null)
    scope.launch {
      val outcome = authService.pair(rawInput)
      when (outcome) {
        is PairResult.Success -> {
          ui = ui.copy(pairing = false, success = true)
          onPaired(null)
        }
        is PairResult.Failure -> ui = ui.copy(pairing = false, error = outcome.message)
        PairResult.Cancelled -> ui = ui.copy(pairing = false)
      }
    }
  }

  Box(modifier = Modifier.fillMaxSize().background(colors.screenBackground)) {
    // 连接剧场：背景 + 立绘（产品 1.12：鲸鱼娘等你接入）
    com.amadeus.whale.theatre.TheatreStage(
      background = "bg-deepseek-seaside-study",
      mood = if (ui.success) com.amadeus.whale.domain.model.AmadeusMood.happy else com.amadeus.whale.domain.model.AmadeusMood.shy,
      sprite = if (ui.success) com.amadeus.whale.domain.model.AmadeusSprite.wag else com.amadeus.whale.domain.model.AmadeusSprite.shy,
    )
    Column(
      modifier = Modifier.align(Alignment.Center).padding(32.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
    ) {
      Text(
        text = when {
          ui.pairing -> "正在把鲸鱼娘接到你的电脑上…"
          ui.success -> "呜…她回来了！"
          firstPairing -> "呜…第一次见面，请把我接到你的电脑上吧"
          else -> "呜…好像还没连上你的电脑呢"
        },
        color = colors.primaryText,
        fontSize = 16.sp,
        textAlign = TextAlign.Center,
        modifier = Modifier.background(
          androidx.compose.ui.graphics.Color(0xCCFFFDF8),
          androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
        ).padding(12.dp),
      )
      if (ui.pairing) {
        Spacer(Modifier.height(16.dp))
        CircularProgressIndicator(modifier = Modifier.width(28.dp).height(28.dp))
      }
      if (ui.error != null) {
        Spacer(Modifier.height(12.dp))
        Text(text = ui.error ?: "", color = colors.danger, fontSize = 13.sp, textAlign = TextAlign.Center)
      }
      Spacer(Modifier.height(24.dp))

      if (ui.manualOpen) {
        OutlinedTextField(
          value = manualInput,
          onValueChange = { manualInput = it },
          placeholder = { Text("https://192.168.0.104:3444/mobile-access/pair#instance=…&token=…") },
          modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        Button(onClick = { startPair(manualInput) }, enabled = manualInput.isNotBlank()) {
          Text("连接")
        }
        Spacer(Modifier.height(8.dp))
      } else {
        Button(onClick = { scanLauncher.launch(Intent(context, ScanActivity::class.java)) }) {
          Text("扫码配对")
        }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = { ui = ui.copy(manualOpen = true) }) {
          Text("手动输入地址")
        }
      }
      if (firstPairing) {
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = onBackToDemo) {
          Text("再看一遍 demo")
        }
      }
    }
  }
}

/**
 * 配对成功后构造真实 Repository（baseUrl = 网关 origin）。
 * 由 MainActivity 注入的工厂调用。
 */
fun buildRepositoryFromAuth(authService: HttpAuthService): com.amadeus.whale.domain.SessionRepository? {
  val session = authService.currentSession() ?: return null
  return com.amadeus.whale.data.HttpSessionRepository(session.origin.serialized, session.client)
}
