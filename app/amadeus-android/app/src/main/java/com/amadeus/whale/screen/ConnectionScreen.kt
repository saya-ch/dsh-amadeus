package com.amadeus.whale.screen

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.amadeus.whale.domain.AuthService
import com.amadeus.whale.domain.PairResult
import com.amadeus.whale.domain.model.AmadeusSprite
import com.amadeus.whale.domain.model.AmadeusTag
import com.amadeus.whale.domain.model.Dialogue
import com.amadeus.whale.scan.ScanActivity
import com.amadeus.whale.theatre.DialogueBox
import com.amadeus.whale.theatre.TheatreStage
import com.amadeus.whale.theme.LocalAmadeusColors
import kotlinx.coroutines.launch

/** 连接演出：当前展示的对话步。 */
private enum class ConnPhase { INTRO0, INTRO1, INTRO2, CHOOSE, PAIRING, SUCCESS, FAILURE }

/**
 * 连接剧场（对话式，产品 1.12 改）：鲸鱼娘等你接入——所有状态用台词演出，
 * 操作（扫码/手动输入/重试）作为对话中的选项浮层，不打断剧场氛围。
 */
@Composable
fun ConnectionScreen(
  firstPairing: Boolean,
  authService: AuthService,
  onPaired: (sessionId: String?) -> Unit,
  onBackToDemo: () -> Unit,
) {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  var phase by remember { mutableStateOf(if (firstPairing) ConnPhase.INTRO0 else ConnPhase.INTRO1) }
  var manualOpen by remember { mutableStateOf(false) }
  var manualInput by remember { mutableStateOf("") }
  var error by remember { mutableStateOf<String?>(null) }
  val colors = LocalAmadeusColors.current

  // 当前展示台词（随 phase 变）
  val line: Dialogue = when (phase) {
    ConnPhase.INTRO0 -> Dialogue("呜……你也睡不着吗？", AmadeusTag(AmadeusSprite.shy))
    ConnPhase.INTRO1 -> Dialogue("第一次见面，请把我接到你的电脑上吧。", AmadeusTag(AmadeusSprite.happy))
    ConnPhase.INTRO2 -> Dialogue("有两个方法可以找到我，你想用哪个？", AmadeusTag(AmadeusSprite.excited))
    ConnPhase.CHOOSE -> Dialogue("扫描电脑上的二维码，或者手动输入地址～", AmadeusTag(AmadeusSprite.excited))
    ConnPhase.PAIRING -> Dialogue("正在把鲸鱼娘接到你的电脑上…", AmadeusTag(AmadeusSprite.thinking))
    ConnPhase.SUCCESS -> Dialogue("呜…她回来了！", AmadeusTag(AmadeusSprite.happy))
    ConnPhase.FAILURE -> Dialogue("呜……没找到你的电脑呢，再试一次？", AmadeusTag(AmadeusSprite.flustered))
  }
  // 打字机：非 PAIRING（配对中有进度圈不打字机）时 typing
  val typing = phase != ConnPhase.PAIRING && phase != ConnPhase.SUCCESS
  // 当前句是否已打字完成（打完才显 ▼ 提示点下一条）
  var typeDone by remember { mutableStateOf(false) }
  // 切 phase 时重置（新台词重新打字）
  LaunchedEffect(line.text) { typeDone = false }

  fun startPair(rawInput: String) {
    phase = ConnPhase.PAIRING
    error = null
    scope.launch {
      val outcome = authService.pair(rawInput)
      when (outcome) {
        is PairResult.Success -> {
          phase = ConnPhase.SUCCESS
          onPaired(null)
        }
        is PairResult.Failure -> {
          phase = ConnPhase.FAILURE
          error = outcome.message
        }
        PairResult.Cancelled -> phase = ConnPhase.CHOOSE
      }
    }
  }

  // 扫码回调
  val scanLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
    androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult(),
  ) { result ->
    val raw = result.data?.getStringExtra("scan_result")
    if (raw != null) startPair(raw)
  }

  // 点击推进对话（打字中=跳到 CHOOSE 前的下一句；选项停留时点空白无副作用）
  // manualOpen 开着时空白点击不推进（避免误收 URL 输入浮层）
  fun onStageTap() {
    if (manualOpen) return
    if (phase == ConnPhase.PAIRING || phase == ConnPhase.SUCCESS) return
    phase = when (phase) {
      ConnPhase.INTRO0 -> if (firstPairing) ConnPhase.INTRO1 else ConnPhase.CHOOSE
      ConnPhase.INTRO1 -> ConnPhase.INTRO2
      ConnPhase.INTRO2 -> ConnPhase.CHOOSE
      ConnPhase.CHOOSE -> ConnPhase.CHOOSE // 选项停留
      ConnPhase.FAILURE -> ConnPhase.CHOOSE
      else -> phase
    }
    if (phase == ConnPhase.CHOOSE) manualOpen = false
  }

  Box(modifier = Modifier.fillMaxSize().background(colors.screenBackground)) {
    TheatreStage(
      background = "bg-deepseek-seaside-study",
      sprite = line.tag.sprite,
    )

    // 全屏点击推进对话（打字中=跳到 CHOOSE 前的下一句；选项停留时点空白无副作用）
    Box(
      modifier = Modifier
        .fillMaxSize()
        .pointerInput(Unit) { detectTapGestures { onStageTap() } },
    )

    // 配对中：对话框上方进度圈（对话框下方是操作位，此 phase 无操作）
    if (phase == ConnPhase.PAIRING) {
      CircularProgressIndicator(
        modifier = Modifier.align(Alignment.Center).width(36.dp).height(36.dp),
        color = colors.accent,
      )
    }

    // 操作浮层：CHOOSE/FAILURE 时对话框上方给操作（扫码/手动/重试）；manualOpen 时 URL 输入
    if (phase == ConnPhase.CHOOSE || phase == ConnPhase.FAILURE || manualOpen) {
      Column(
        modifier = Modifier
          .align(Alignment.BottomCenter)
          // 浮层停在对话框上方：框高 = 屏 5/21，再加 24dp 间隙
          .padding(bottom = (androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp.dp * 5f / 21f) + 26.dp)
          .fillMaxWidth()
          .padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
      ) {
        if (error != null) {
          Text(
            text = error.orEmpty(),
            color = colors.danger,
            fontSize = 13.sp,
            modifier = Modifier.background(Color.White.copy(alpha = 0.7f), RoundedCornerShape(8.dp)).padding(6.dp),
          )
          Spacer(Modifier.height(8.dp))
        }
        if (manualOpen) {
          OutlinedTextField(
            value = manualInput,
            onValueChange = { manualInput = it },
            placeholder = { Text("https://…/amadeus/pair#instance=…&token=…", fontSize = 13.sp) },
            modifier = Modifier.fillMaxWidth(),
          )
          Spacer(Modifier.height(8.dp))
          Row {
            OutlinedButton(onClick = { manualOpen = false }, modifier = Modifier.weight(1f)) { Text("返回") }
            Spacer(Modifier.width(12.dp))
            Button(
              onClick = { startPair(manualInput) },
              enabled = manualInput.isNotBlank(),
              modifier = Modifier.weight(1f),
            ) { Text("连接") }
          }
        } else {
          Row(modifier = Modifier.fillMaxWidth()) {
            Button(
              onClick = { scanLauncher.launch(Intent(context, ScanActivity::class.java)) },
              modifier = Modifier.weight(1f),
            ) { Text("扫码配对") }
            Spacer(Modifier.width(12.dp))
            OutlinedButton(
              onClick = {
                manualOpen = true
                if (phase != ConnPhase.CHOOSE) phase = ConnPhase.CHOOSE
              },
              modifier = Modifier.weight(1f),
            ) { Text("手动输入地址") }
          }
          if (firstPairing) {
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = onBackToDemo, modifier = Modifier.fillMaxWidth()) {
              Text("再看一遍 demo", fontSize = 13.sp)
            }
          }
        }
      }
    }

    // 对话框（点击推进；SUCCESS 时由 onPaired 立刻切走，不在此停留）
    DialogueBox(
      text = line.text,
      typing = typing,
      userText = null,
      // 打完才显 ▼（提示点下一条）；CHOOSE 有操作按钮不显
      showArrow = typeDone && phase != ConnPhase.CHOOSE,
      onClick = { onStageTap() },
      onTypingFinished = { typeDone = true },
      modifier = Modifier.align(Alignment.BottomCenter),
    )
  }
}

private val Color = androidx.compose.ui.graphics.Color
