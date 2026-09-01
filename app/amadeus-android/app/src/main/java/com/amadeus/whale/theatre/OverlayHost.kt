package com.amadeus.whale.theatre

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
          HistorySheet(overlay.dialogues, onClose)
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
      AmadeusOverlay(visible = true, from = Alignment.BottomCenter, modifier = modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().background(com.amadeus.whale.theme.LocalAmadeusColors.current.overlayScrim).clickable(onClick = onClose))
        Box(Modifier.align(Alignment.BottomCenter)) {
          ChoiceSheet(overlay.choice, onResolveChoice, onClose)
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
        items(activities.takeLast(10).asReversed(), key = { "${it.kind}-${it.title}" }) { a ->
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

/** 对话记录侧栏（产品 1.9：只显示参加演出的对话）。 */
@Composable
private fun HistorySheet(dialogues: List<Dialogue>, onClose: () -> Unit) {
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
    if (dialogues.isEmpty()) {
      Text("还没有对话", color = colors.secondaryText, fontSize = 13.sp)
    } else {
      LazyColumn {
        items(dialogues.takeLast(20).asReversed(), key = { it.text }) { d ->
          Column(modifier = Modifier.padding(vertical = 6.dp)) {
            Text(text = d.text, color = colors.primaryText, fontSize = 14.sp)
          }
        }
      }
    }
  }
}

/** 报告窗口（产品 1.11：报告专用页质感）。 */
@Composable
private fun ReportWindow(title: String, body: String, onClose: () -> Unit) {
  val colors = LocalAmadeusColors.current
  Card(
    modifier = Modifier.fillMaxWidth(0.9f).fillMaxHeight(0.7f),
    colors = CardDefaults.cardColors(containerColor = colors.cardBackground),
    elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
  ) {
    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Text("报告", color = colors.accent, fontSize = 12.sp)
        Spacer(Modifier.weight(1f))
        Text("✕", color = colors.secondaryText, fontSize = 18.sp, modifier = Modifier.clickable(onClick = onClose))
      }
      Spacer(Modifier.height(4.dp))
      Text(title, color = colors.primaryText, fontSize = 16.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
      Spacer(Modifier.height(8.dp))
      val scroll = androidx.compose.foundation.rememberScrollState()
      Column(
        modifier = Modifier
          .weight(1f)
          .verticalScroll(scroll),
      ) {
        Text(body, color = colors.primaryText, fontSize = 13.sp)
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

/** 选项（产品 1.11：ask_user_question 选项立绘前方）。 */
@Composable
private fun ChoiceSheet(choice: Choice, onResolve: (Choice, String) -> Unit, onClose: () -> Unit) {
  val colors = LocalAmadeusColors.current
  Column(
    modifier = Modifier
      .fillMaxWidth()
      .background(colors.sheetBackground, RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
      .padding(20.dp),
  ) {
    Text(choice.question, color = colors.primaryText, fontSize = 16.sp)
    Spacer(Modifier.height(12.dp))
    choice.options.forEach { opt ->
      Button(
        onClick = { onResolve(choice, opt.label) },
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
      ) {
        Text(opt.label)
      }
      if (!opt.description.isNullOrBlank()) {
        Text(opt.description, color = colors.secondaryText, fontSize = 12.sp, modifier = Modifier.padding(start = 8.dp, bottom = 4.dp))
      }
    }
    Spacer(Modifier.height(4.dp))
    Text("✕ 暂不选择", color = colors.secondaryText, fontSize = 13.sp, modifier = Modifier.align(Alignment.CenterHorizontally).clickable(onClick = onClose))
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
