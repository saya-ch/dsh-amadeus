package com.amadeus.whale.screen

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import androidx.compose.ui.unit.sp
import kotlin.math.*
import com.amadeus.whale.data.store.DevicePrefsStore
import com.amadeus.whale.domain.model.AmadeusSprite
import com.amadeus.whale.theatre.DialogueBox
import com.amadeus.whale.theatre.OverlayHost
import com.amadeus.whale.theatre.TheatreStage
import com.amadeus.whale.theatre.TheatreViewModel
import com.amadeus.whale.theme.LocalAmadeusColors

/**
 * 剧场（产品 1.8/架构 3.11）：Stage + DialogueBox + 输入角落唤出（占一行）+ 顶部操作 + OverlayHost。
 * 状态提升：全部状态从 TheatreViewModel 来，组件纯展示。
 */
@Composable
fun TheatreScreen(
  viewModel: TheatreViewModel,
  prefsStore: DevicePrefsStore,
  gatewayUrl: String?,
  onOpenSaveSlot: () -> Unit,
  onReplayDemo: () -> Unit,
  onDisconnect: () -> Unit,
  onReconnect: () -> Unit = {},
  demoMode: Boolean = false,
  onDemoFinished: () -> Unit = {},
) {
  val state by viewModel.uiState.collectAsState()
  val colors = LocalAmadeusColors.current
  // 用户设置里的背景偏好（产品 1.10；剧场背景 = 偏好优先，think/tool 态临时切协作工坊）
  val userBackground by prefsStore.flow.collectAsState(initial = com.amadeus.whale.data.store.DevicePrefs())
  var inputOpen by remember { mutableStateOf(false) }
  var inputText by remember { mutableStateOf("") }
  // 当前句打字完成标记（演出态 ▼ 提示）
  var typeDone by remember { mutableStateOf(false) }
  LaunchedEffect(state.dialogue?.text) { typeDone = false }
  // 立绘：有新对话跟它的 sprite；发送后（dialogue=null）保持上一轮表情，不回 normal
  val liveSprite = state.dialogue?.tag?.sprite
  var lastSprite by remember { mutableStateOf(AmadeusSprite.normal) }
  LaunchedEffect(liveSprite) {
    if (liveSprite != null) lastSprite = liveSprite
  }

  // demo 播完 → 回调 onDemoFinished（跳连接页）
  LaunchedEffect(state.demoFinished) {
    if (state.demoFinished) onDemoFinished()
  }

  Box(modifier = Modifier.fillMaxSize()) {
    // 舞台：demo 固定月夜宫殿；真实模式恒为用户在设置里选的背景；立绘随 sprite
    TheatreStage(
      background = if (demoMode) {
        "palace-night"
      } else {
        userBackground.background.ifBlank { "bg-claude-writing-study" }
      },
      sprite = liveSprite ?: lastSprite,
    )
    // 全屏浮动小字（幕后活动生动化）：随机位置冒字、上漂渐隐，避开底部对话框
    val flicks by viewModel.flicks.collectAsState()
    if (flicks.isNotEmpty()) {
      FloatingNoteLayer(flicks = flicks)
    }
    // 输入框展开时的空白点击层：点输入框外任意处 → 收回（保留已输入文本）
    if (inputOpen) {
      Box(
        modifier = Modifier
          .fillMaxSize()
          .pointerInput(Unit) {
            detectTapGestures { inputOpen = false }
          },
      )
    } else {
      // 全屏空白处点击层：点任意空白 → 打断打字/演出推进（不展开输入框）；点击处小范围涟漪
      TapRippleLayer(
        onTap = { viewModel.onTap() },
      )
    }
    // 底部：输入展开（覆盖在对话框内部底部）+ 对话框
    val boxH = LocalConfiguration.current.screenHeightDp.dp * 5f / 21f
    Box(
      modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth(),
    ) {
      DialogueBox(
        text = state.dialogue?.text ?: "（等待鲸鱼娘说话…）",
        typing = state.typing,
        userText = state.userText,
        // ▼ 只在演出态（demo 点击翻页）且本句打字完成时显示，跟随句末
        showArrow = demoMode && typeDone,
        // 工作中旁白句末小动画符号（真实模式任务中，非最终回答）
        showWorking = !demoMode && (state.dialogue?.working == true) && typeDone,
        onClick = {
          if (inputOpen) {
            // 输入框展开时点对话框 → 收回（保留已输入文本）
            inputOpen = false
          } else {
            // 点对话框：打断打字 / demo 演出推进（不展开输入框，展开只靠铅笔）
            viewModel.onTap()
          }
        },
        onTypingFinished = { typeDone = true },
      )
      // 输入区：展开时占对话框内底部一行；铅笔在对话框右下角（纯图标无圆底）
      if (inputOpen) {
        Row(
          modifier = Modifier
            .align(Alignment.BottomCenter)
            .fillMaxWidth()
            .height(boxH * 0.42f)
            .padding(start = 26.dp, end = 26.dp, bottom = 10.dp),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          OutlinedTextField(
            value = inputText,
            onValueChange = { inputText = it },
            modifier = Modifier.weight(1f),
            placeholder = { Text("和鲸鱼娘说点什么…", fontSize = 14.sp) },
            textStyle = androidx.compose.ui.text.TextStyle(fontSize = 15.sp),
            singleLine = true,
          )
          Spacer(Modifier.width(10.dp))
          // 发送键：输入框右端（铅笔图标同款浅色，无圆底）
          Text(
            text = "➤",
            fontSize = 18.sp,
            color = Color(0xFFFFE0A0),
            modifier = Modifier
              .clickable {
                if (inputText.isNotBlank()) {
                  viewModel.send(inputText)
                  inputText = ""
                  inputOpen = false
                }
              }
              .padding(6.dp),
          )
        }
      } else if (!demoMode) {
        // 铅笔：对话框右下角，纯图标（无圆形底）
        Text(
          text = "✎",
          fontSize = 20.sp,
          color = Color(0xFFFFE0A0),
          modifier = Modifier
            .align(Alignment.BottomEnd)
            .padding(end = 22.dp, bottom = 12.dp)
            .clickable { inputOpen = true },
        )
      }
    }
    // 顶部栏：记录/幕后 + 读档/设置，整宽一条，背景 = 与文本框同色（0xFF0B1830）的上实下隐渐变
    val dialogueInk = Color(0xFF0B1830)
    val topInk = Color(0xFFFFF5E6) // 顶部按钮字：与文本框正文同色（暖白）
    val topBarH = 44.dp
    Box(
      modifier = Modifier
        .fillMaxWidth()
        .align(Alignment.TopCenter)
        .statusBarsPadding()
        .height(topBarH)
        .background(
          Brush.verticalGradient(
            colorStops = arrayOf(
              0f to dialogueInk.copy(alpha = 0.85f),
              0.55f to dialogueInk.copy(alpha = 0.45f),
              1f to dialogueInk.copy(alpha = 0f),
            ),
          ),
        ),
    ) {
      Row(
        modifier = Modifier.fillMaxSize().padding(horizontal = 4.dp),
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Row {
          TextButton(onClick = { viewModel.openHistory() }) { Text("记录", color = topInk) }
          TextButton(onClick = { viewModel.openEventLog() }) { Text("幕后", color = topInk) }
        }
        Row {
          if (!demoMode) TextButton(onClick = onOpenSaveSlot) { Text("读档", color = topInk) }
          TextButton(onClick = { viewModel.openOverlay(com.amadeus.whale.theatre.OverlayState.Settings) }) {
            Text("设置", color = topInk)
          }
        }
      }
    }

    // 覆盖层（架构 3.12）
    OverlayHost(
      overlay = state.overlay,
      prefsStore = prefsStore,
      gatewayUrl = gatewayUrl,
      onClose = { viewModel.closeOverlay() },
      onReplayDemo = onReplayDemo,
      onDisconnect = onDisconnect,
      onReconnect = onReconnect,
      onResolveChoice = { choice, label -> viewModel.resolveChoice(choice, label) },
      onDismissChoice = { choice -> viewModel.dismissChoice(choice) },
      onDecideApproval = { approval, allowed -> viewModel.decideApproval(approval, allowed) },
    )
  }
}

/**
 * 全屏点击层 + 点击处柔和光晕涟漪（小范围，不扩散全屏）。
 * 视觉：扩散的光晕圆（多层叠出径向渐变）＋中心细环，整体 blur 柔化（API 31+；低版本自动退化）。
 */
@Composable
private fun TapRippleLayer(onTap: () -> Unit) {
  var tapPoint by remember { mutableStateOf<Offset?>(null) }
  var rippleKey by remember { mutableStateOf(0) }
  Box(
    modifier = Modifier
      .fillMaxSize()
      .pointerInput(Unit) {
        detectTapGestures { offset ->
          tapPoint = offset
          rippleKey++
          onTap()
        }
      },
  ) {
    // 点击处光晕涟漪动画（仅在有点击记录时播放一次）
    val point = tapPoint
    if (point != null) {
      val progress = remember { Animatable(0f) }
      LaunchedEffect(rippleKey, point) {
        progress.snapTo(0f)
        progress.animateTo(1f, animationSpec = tween(650, easing = FastOutSlowInEasing))
      }
      val p = progress.value
      // 模糊光晕（Compose BlurEffect，自动在不支持的设备返回 null）
      val blurEffect = remember {
        androidx.compose.ui.graphics.BlurEffect(24f, 24f, androidx.compose.ui.graphics.TileMode.Clamp)
          .takeIf { it.isSupported() }
      }
      Canvas(
        modifier = Modifier
          .fillMaxSize()
          .graphicsLayer { renderEffect = blurEffect },
      ) {
        if (p < 1f) {
          val radius = 10.dp.toPx() + (72.dp.toPx() - 10.dp.toPx()) * p
          val fade = (1f - p)
          // 光晕主体（柔和填充，靠多层+blur 呈现光晕）
          drawCircle(
            color = Color.White.copy(alpha = 0.20f * fade),
            radius = radius,
            center = point,
          )
          drawCircle(
            color = Color.White.copy(alpha = 0.14f * fade),
            radius = radius * 0.72f,
            center = point,
          )
          // 外圈细环（扩散感）
          drawCircle(
            color = Color.White.copy(alpha = 0.5f * fade),
            radius = radius,
            center = point,
            style = Stroke(width = 1.5.dp.toPx()),
          )
          // 中心亮核（点击闪光）
          drawCircle(
            color = Color.White.copy(alpha = 0.35f * fade),
            radius = 3.dp.toPx() + 10.dp.toPx() * p,
            center = point,
          )
        }
      }
    }
  }
}

/**
 * 全屏浮动小字层（幕后活动生动化，产品 1.20）：
 * 每条活动在屏幕随机位置冒一行小字——出现 → 上漂 ~50dp → 渐隐消失（2.8s）。
 * 事件流（幕后活动）：文本框上方右侧；新条从下滚出顶起旧条上移，顶部渐隐，上限 5。
 * 黑字白描边、右对齐、限宽 2/3 屏。
 */
@Composable
private fun FloatingNoteLayer(flicks: List<com.amadeus.whale.theatre.FlickNote>) {
  val screenW = LocalConfiguration.current.screenWidthDp.dp
  val screenH = LocalConfiguration.current.screenHeightDp.dp
  val dialogH = screenH * 5f / 21f   // 与底部对话框同高
  val widthCap = screenW * 2f / 3f   // 宽度上限 2/3 屏
  val fadeMs = 9000                   // 与 VM 移除(9s)同源：尾段 1.35s 渐隐，结束时正好移除
  Box(
    modifier = Modifier
      .fillMaxSize()
      .padding(end = 14.dp, bottom = dialogH + 2.dp),
  ) {
    Column(
      modifier = Modifier
        .align(Alignment.BottomEnd)
        .widthIn(max = widthCap)
        .animateContentSize(tween(240)),
      horizontalAlignment = Alignment.End,
    ) {
      flicks.reversed().forEach { note ->
        val p = remember(note.id) { Animatable(if (note.fading) 1f else 0f) }
        LaunchedEffect(note.id, note.fading) {
          p.animateTo(1f, animationSpec = tween(if (note.fading) 600 else fadeMs, easing = androidx.compose.animation.core.LinearEasing))
        }
        val v = p.value
        // 淡入上滚(首 2.5%)→停留→尾 15% 渐隐；fading 条统一快速淡出
        val enter = if (note.fading) 1f else (v / 0.025f).coerceIn(0f, 1f)
        val alpha = when {
          note.fading -> (1f - v).coerceIn(0f, 1f)
          v > 0.85f -> ((1f - v) / 0.15f).coerceIn(0f, 1f)
          else -> enter
        }
        val rise = if (note.fading) 0.dp else 16.dp * (1f - enter)
        Text(
          text = note.text,
          color = Color(0xFF101014).copy(alpha = alpha),
          fontSize = 13.sp,
          fontWeight = androidx.compose.ui.text.font.FontWeight.Medium,
          fontFamily = com.amadeus.whale.theme.AmadeusFontFamily,
          maxLines = 1,
          overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
          style = androidx.compose.ui.text.TextStyle(
            shadow = androidx.compose.ui.graphics.Shadow(
              color = Color.White.copy(alpha = 0.95f * alpha),
              offset = androidx.compose.ui.geometry.Offset.Zero,
              blurRadius = 3f,
            ),
          ),
          modifier = Modifier
            .widthIn(max = widthCap)
            .padding(vertical = 1.dp)
            .offset(y = rise),
        )
      }
    }
  }
}
