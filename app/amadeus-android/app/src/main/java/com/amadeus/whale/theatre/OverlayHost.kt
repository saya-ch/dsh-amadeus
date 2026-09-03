package com.amadeus.whale.theatre

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.amadeus.whale.data.store.DevicePrefsStore
import com.amadeus.whale.domain.model.Choice
import com.amadeus.whale.domain.model.Dialogue
import com.amadeus.whale.theme.AmadeusOverlay
import com.amadeus.whale.theme.LocalAmadeusColors

/**
 * 统一覆盖层宿主（架构 3.12）：接收 [OverlayState?]，统一渲染 + 遮罩 + 过渡。
 * 单层不叠加；设置从底部滑入，侧栏从边缘滑入，窗口居中。
 */
@Composable
fun BoxScope.OverlayHost(
  overlay: OverlayState?,
  prefsStore: DevicePrefsStore,
  gatewayUrl: String?,
  onClose: () -> Unit,
  onReplayDemo: () -> Unit,
  onDisconnect: () -> Unit,
  onReconnect: () -> Unit,
  onResolveChoice: (Choice, String) -> Unit,
  onDecideApproval: (com.amadeus.whale.domain.model.ApprovalRequest, Boolean) -> Unit,
  onDismissChoice: (Choice) -> Unit = { onClose() },
  modifier: Modifier = Modifier,
) {
  when (overlay) {
    is OverlayState.Settings -> {
      AmadeusOverlay(visible = true, from = Alignment.BottomCenter, modifier = modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().background(com.amadeus.whale.theme.LocalAmadeusColors.current.overlayScrim).clickable(onClick = onClose))
        Box(Modifier.align(Alignment.BottomCenter)) {
          SettingsOverlay(
            prefsStore = prefsStore,
            onClose = onClose,
            onReplayDemo = onReplayDemo,
            onDisconnect = onDisconnect,
            onReconnect = onReconnect,
            gatewayUrl = gatewayUrl,
          )
        }
      }
    }
    is OverlayState.EventLog -> {
      AmadeusOverlay(visible = true, from = Alignment.CenterEnd, modifier = modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().background(com.amadeus.whale.theme.LocalAmadeusColors.current.overlayScrim).clickable(onClick = onClose))
        Box(Modifier.align(Alignment.CenterEnd).fillMaxHeight().fillMaxWidth(0.78f)) {
          EventLogSheet(overlay.activities, onClose)
        }
      }
    }
    is OverlayState.History -> {
      AmadeusOverlay(visible = true, from = Alignment.CenterStart, modifier = modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().background(com.amadeus.whale.theme.LocalAmadeusColors.current.overlayScrim).clickable(onClick = onClose))
        Box(Modifier.align(Alignment.CenterStart).fillMaxHeight().fillMaxWidth(0.78f)) {
          HistorySheet(overlay.lines, onClose)
        }
      }
    }
    is OverlayState.Report -> {
      AmadeusOverlay(visible = true, from = Alignment.BottomCenter, modifier = modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().background(com.amadeus.whale.theme.LocalAmadeusColors.current.overlayScrim).clickable(onClick = onClose))
        Box(Modifier.align(Alignment.Center)) {
          ReportWindow(overlay.title, overlay.body, onClose)
        }
      }
    }
    is OverlayState.Preview -> {
      AmadeusOverlay(visible = true, from = Alignment.BottomCenter, modifier = modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().background(com.amadeus.whale.theme.LocalAmadeusColors.current.overlayScrim).clickable(onClick = onClose))
        Box(Modifier.align(Alignment.Center)) {
          PreviewWindow(overlay.title, overlay.body, onClose)
        }
      }
    }
    is OverlayState.ChoicePrompt -> {
      AmadeusOverlay(visible = true, from = Alignment.Center, modifier = modifier.fillMaxSize()) {
        // 点背景/暂时不选 = 取消该提问（告诉服务端，否则 agent 挂死）
        Box(Modifier.fillMaxSize().background(com.amadeus.whale.theme.LocalAmadeusColors.current.overlayScrim).clickable(onClick = { onDismissChoice(overlay.choice) }))
        // 内容超高时（选项+描述多）会顶出屏：限制高度让 Center 真正居中，内部可滚
        Box(
          Modifier
            .align(Alignment.Center)
            .padding(horizontal = 28.dp)
            .fillMaxHeight(0.86f)
            .widthIn(max = 560.dp)
            .verticalScroll(androidx.compose.foundation.rememberScrollState()),
        ) {
          ChoiceSheet(overlay.choice, onResolveChoice, onDismissChoice)
        }
      }
    }
    is OverlayState.ApprovalPrompt -> {
      AmadeusOverlay(visible = true, from = Alignment.BottomCenter, modifier = modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().background(com.amadeus.whale.theme.LocalAmadeusColors.current.overlayScrim).clickable(onClick = onClose))
        Box(Modifier.align(Alignment.Center)) {
          ApprovalCard(overlay.approval, onDecideApproval, onClose)
        }
      }
    }
    null -> Unit
  }
}

/** 事件流小窗（产品 1.9：实时最近 10 条，透明底侧边栏）。 */
@Composable
private fun EventLogSheet(activities: List<com.amadeus.whale.domain.model.Activity>, onClose: () -> Unit) {
  val colors = LocalAmadeusColors.current
  Column(
    modifier = Modifier
      .fillMaxSize()
      .background(colors.sheetBackground, RoundedCornerShape(topStart = 16.dp, bottomStart = 16.dp))
      .padding(16.dp),
  ) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      Text("幕后", color = colors.primaryText, fontSize = 16.sp, modifier = Modifier.weight(1f))
      Text("✕", color = colors.secondaryText, fontSize = 18.sp, modifier = Modifier.clickable(onClick = onClose))
    }
    Spacer(Modifier.height(8.dp))
    if (activities.isEmpty()) {
      Text("暂无幕后活动", color = colors.secondaryText, fontSize = 13.sp)
    } else {
      LazyColumn {
        val reversed = activities.takeLast(10).asReversed()
        itemsIndexed(reversed, key = { index, item -> "${reversed.size - index}-${item.kind}-${item.title}" }) { _, a ->
          ActivityRow(a)
        }
      }
    }
  }
}

@Composable
private fun ActivityRow(a: com.amadeus.whale.domain.model.Activity) {
  val colors = LocalAmadeusColors.current
  Column(modifier = Modifier.padding(vertical = 6.dp)) {
    Text(text = a.title, color = colors.primaryText, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
    if (a.detail.isNotBlank()) {
      Text(text = a.detail, color = colors.secondaryText, fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
  }
}

/** 对话记录侧栏（产品 1.9：鲸鱼娘话 + 用户发言，按序，用户蓝字靠右）。 */
@Composable
private fun HistorySheet(lines: List<com.amadeus.whale.domain.ChatLine>, onClose: () -> Unit) {
  val colors = LocalAmadeusColors.current
  Column(
    modifier = Modifier
      .fillMaxSize()
      .background(colors.sheetBackground, RoundedCornerShape(topEnd = 16.dp, bottomEnd = 16.dp))
      .padding(16.dp),
  ) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      Text("对话记录", color = colors.primaryText, fontSize = 16.sp, modifier = Modifier.weight(1f))
      Text("✕", color = colors.secondaryText, fontSize = 18.sp, modifier = Modifier.clickable(onClick = onClose))
    }
    Spacer(Modifier.height(8.dp))
    if (lines.isEmpty()) {
      Text("还没有对话", color = colors.secondaryText, fontSize = 13.sp)
    } else {
      LazyColumn {
        val reversed = lines.takeLast(20).asReversed()
        itemsIndexed(reversed, key = { index, _ -> "${reversed.size - index}-${reversed[index].text}" }) { _, line ->
          val isUser = line.speaker == com.amadeus.whale.domain.ChatLine.ChatSpeaker.USER
          Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
            horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
          ) {
            Text(
              text = line.text,
              color = if (isUser) Color(0xFF1B5FA8) else colors.primaryText,
              fontSize = 14.sp,
              modifier = Modifier.fillMaxWidth(if (isUser) 0.8f else 1f),
            )
          }
        }
      }
    }
  }
}

/** 报告窗口（产品 1.9/1.11：报告专用页——卷轴/信件质感，鲸鱼娘递给你的一份东西）。
 * 9/2 升级：深蓝标题横幅 + 信纸暖底 + 金线卷轴边框 + 底部纹章收边。 */
@Composable
private fun ReportWindow(title: String, body: String, onClose: () -> Unit) {
  val colors = LocalAmadeusColors.current
  Card(
    modifier = Modifier.fillMaxWidth(0.92f).fillMaxHeight(0.78f),
    colors = CardDefaults.cardColors(containerColor = colors.sheetBackground),
    elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
  ) {
    Column(modifier = Modifier.fillMaxSize()) {
      // 深蓝标题横幅（maid-atelier settings-frame，CC BY-NC-SA 4.0）
      Box(
        modifier = Modifier
          .fillMaxWidth()
          .height(52.dp)
          .clip(RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp)),
      ) {
        AssetImage(
          name = "maid-settings-frame-v1",
          modifier = Modifier.fillMaxSize(),
          contentScale = androidx.compose.ui.layout.ContentScale.Crop,
        )
        Text(
          text = "✉ 报告",
          color = colors.namePlateText,
          fontSize = 14.sp,
          modifier = Modifier.align(Alignment.CenterStart).padding(start = 16.dp),
        )
        Text(
          text = "✕",
          color = colors.namePlateText,
          fontSize = 18.sp,
          modifier = Modifier.align(Alignment.CenterEnd).padding(end = 16.dp).clickable(onClick = onClose),
        )
      }
      // 信纸主体：蕾丝金线顶 + 暖纸底 + 金描边（卷轴/信件感）
      Column(modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 10.dp)) {
        AssetImage(
          name = "maid-composer-frame-v4",
          modifier = Modifier.fillMaxWidth().height(20.dp),
        )
        Column(
          modifier = Modifier
            .weight(1f)
            .fillMaxWidth()
            .background(colors.cardBackground)
            .border(1.dp, colors.dialogueBorder, RoundedCornerShape(0.dp))
            .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
          Text(title, color = colors.primaryText, fontSize = 17.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
          Spacer(Modifier.height(8.dp))
          val scroll = androidx.compose.foundation.rememberScrollState()
          Column(
            modifier = Modifier
              .weight(1f)
              .verticalScroll(scroll),
          ) {
            Text(body, color = colors.primaryText, fontSize = 14.sp, lineHeight = 22.sp)
          }
        }
        // 底部纹章收边
        Box(modifier = Modifier.fillMaxWidth().padding(top = 2.dp), contentAlignment = Alignment.Center) {
          AssetImage(
            name = "maid-bottom-crest-v1",
            modifier = Modifier.fillMaxWidth(0.3f).height(22.dp),
          )
        }
      }
    }
  }
}

/** 预览窗口（产品 1.11）。 */
@Composable
private fun PreviewWindow(title: String, body: String, onClose: () -> Unit) {
  val colors = LocalAmadeusColors.current
  Card(
    modifier = Modifier.fillMaxWidth(0.9f).fillMaxHeight(0.6f),
    colors = CardDefaults.cardColors(containerColor = colors.cardBackground),
    elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
  ) {
    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Text("预览", color = colors.accent, fontSize = 12.sp)
        Spacer(Modifier.weight(1f))
        Text("✕", color = colors.secondaryText, fontSize = 18.sp, modifier = Modifier.clickable(onClick = onClose))
      }
      Spacer(Modifier.height(4.dp))
      Text(title, color = colors.primaryText, fontSize = 16.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
      Spacer(Modifier.height(8.dp))
      val previewScroll = androidx.compose.foundation.rememberScrollState()
      Column(
        modifier = Modifier
          .weight(1f)
          .verticalScroll(previewScroll),
      ) {
        Text(body, color = colors.primaryText, fontSize = 13.sp)
      }
    }
  }
}

/** 选项（产品 1.11：ask_user_question 选项居中演出，galgame 选项卡）。 */
@Composable
private fun ChoiceSheet(choice: Choice, onResolve: (Choice, String) -> Unit, onDismiss: (Choice) -> Unit) {
  val colors = LocalAmadeusColors.current
  val gold = Color(0xFFE8C36A)
  val ink = Color(0xFF0B1830)
  Column(
    modifier = Modifier
      .fillMaxWidth()
      .background(ink.copy(alpha = 0.92f), RoundedCornerShape(18.dp))
      .padding(22.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    // 问题（居中，鲸鱼娘在问）
    Text(
      choice.question,
      color = Color(0xFFFFF5E6),
      fontSize = 17.sp,
      lineHeight = 26.sp,
      textAlign = TextAlign.Center,
      fontFamily = com.amadeus.whale.theme.AmadeusFontFamily,
      modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
    )
    // 竖排选项（金色描边选项卡）
    choice.options.forEach { opt ->
      Button(
        onClick = { onResolve(choice, opt.label) },
        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF22304A), contentColor = Color(0xFFFFF5E6)),
        shape = RoundedCornerShape(12.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, gold.copy(alpha = 0.55f)),
        modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp),
      ) {
        Text(opt.label, fontSize = 15.sp, fontFamily = com.amadeus.whale.theme.AmadeusFontFamily)
      }
      if (!opt.description.isNullOrBlank()) {
        Text(
          opt.description,
          color = Color(0xFF9DC9FF),
          fontSize = 12.sp,
          textAlign = TextAlign.Center,
          modifier = Modifier.padding(start = 8.dp, end = 8.dp, bottom = 4.dp),
        )
      }
    }
    Spacer(Modifier.height(6.dp))
    Text(
      "暂时不选",
      color = Color(0x99FFF5E6),
      fontSize = 13.sp,
      fontFamily = com.amadeus.whale.theme.AmadeusFontFamily,
      modifier = Modifier.clickable(onClick = { onDismiss(choice) }),
    )
  }
}

/** 审批卡片（方案 B：App 端批准/拒绝，产品 1.12 手机遥控）。 */
@Composable
private fun ApprovalCard(
  approval: com.amadeus.whale.domain.model.ApprovalRequest,
  onDecide: (com.amadeus.whale.domain.model.ApprovalRequest, Boolean) -> Unit,
  onClose: () -> Unit,
) {
  val colors = LocalAmadeusColors.current
  Card(
    modifier = Modifier.fillMaxWidth(0.86f),
    colors = CardDefaults.cardColors(containerColor = colors.cardBackground),
    elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
  ) {
    Column(modifier = Modifier.fillMaxSize().padding(20.dp)) {
      Text("需要批准", color = colors.accent, fontSize = 12.sp)
      Spacer(Modifier.height(4.dp))
      Text(
        text = "鲸鱼娘想调用 ${approval.toolName}",
        color = colors.primaryText,
        fontSize = 16.sp,
      )
      if (!approval.reason.isNullOrBlank()) {
        Spacer(Modifier.height(8.dp))
        Text(
          text = approval.reason,
          color = colors.secondaryText,
          fontSize = 13.sp,
        )
      }
      Spacer(Modifier.height(20.dp))
      Row {
        Button(
          onClick = { onDecide(approval, true) },
          modifier = Modifier.weight(1f).padding(end = 6.dp),
        ) { Text("批准") }
        OutlinedButton(
          onClick = { onDecide(approval, false) },
          modifier = Modifier.weight(1f).padding(start = 6.dp),
        ) { Text("拒绝") }
      }
      Spacer(Modifier.height(6.dp))
      Text(
        text = "✕ 稍后再说",
        color = colors.secondaryText,
        fontSize = 13.sp,
        modifier = Modifier.align(Alignment.CenterHorizontally).clickable(onClick = onClose),
      )
    }
  }
}
