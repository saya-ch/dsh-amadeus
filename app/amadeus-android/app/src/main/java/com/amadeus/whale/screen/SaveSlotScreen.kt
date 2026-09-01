package com.amadeus.whale.screen

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.amadeus.whale.domain.SessionRepository
import com.amadeus.whale.domain.model.AmadeusSession
import com.amadeus.whale.theme.LocalAmadeusColors
import kotlinx.coroutines.launch

/**
 * 读档页（产品 1.10 / 架构 3.15）：按工作区分组 + 组内倒序 + 新建仪式感。
 */
@Composable
fun SaveSlotScreen(
  repository: SessionRepository,
  onOpenSession: (String) -> Unit,
  onBack: () -> Unit,
) {
  val colors = LocalAmadeusColors.current
  var sessions by remember { mutableStateOf<List<AmadeusSession>>(emptyList()) }
  val scope = rememberCoroutineScope()

  LaunchedEffect(Unit) { sessions = repository.list() }

  Column(modifier = Modifier.fillMaxSize().background(colors.screenBackground)) {
    // 顶部标题
    Text(
      text = "读档",
      style = androidx.compose.material3.MaterialTheme.typography.headlineSmall,
      fontWeight = FontWeight.Bold,
      color = colors.primaryText,
      modifier = Modifier.padding(20.dp),
    )
    // 新建（"开启新的一天"仪式感，产品 1.10）
    Button(
      onClick = {
        scope.launch {
          val created = repository.create()
          onOpenSession(created.id)
        }
      },
      modifier = Modifier.padding(horizontal = 20.dp).fillMaxWidth(),
    ) {
      Text("＋ 开启新的一天")
    }
    Spacer(Modifier.height(12.dp))
    // 按工作区分组 + 组内倒序
    LazyColumn(modifier = Modifier.fillMaxSize()) {
      val grouped = sessions.groupBy { it.workspace.ifEmpty { "默认工作区" } }
      grouped.forEach { (workspace, list) ->
        item(key = "ws_$workspace") {
          Text(
            text = workspace,
            color = colors.secondaryText,
            fontSize = 13.sp,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
          )
        }
        items(list.sortedByDescending { it.updatedAt }, key = { it.id }) { session ->
          SessionCard(
            session = session,
            onClick = { onOpenSession(session.id) },
            onRename = { scope.launch { repository.rename(session.id, it) } },
            onDelete = { scope.launch { repository.archive(session.id); sessions = repository.list() } },
          )
        }
      }
    }
    // 底部返回
    Box(modifier = Modifier.padding(12.dp)) {
      OutlinedButton(onClick = onBack) { Text("返回") }
    }
  }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SessionCard(
  session: AmadeusSession,
  onClick: () -> Unit,
  onRename: (String) -> Unit,
  onDelete: () -> Unit,
) {
  val colors = LocalAmadeusColors.current
  var menuOpen by remember { mutableStateOf(false) }
  var renaming by remember { mutableStateOf(false) }
  var renameText by remember { mutableStateOf(session.title) }

  Card(
    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp),
    colors = CardDefaults.cardColors(containerColor = colors.cardBackground),
    elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
  ) {
    Column(
      modifier = Modifier
        .combinedClickable(
          onClick = onClick,
          onLongClick = { menuOpen = true },
        )
        .padding(14.dp),
    ) {
      Text(
        text = session.title.ifEmpty { "未命名" },
        color = colors.primaryText,
        fontWeight = FontWeight.Medium,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
      Spacer(Modifier.height(4.dp))
      Text(
        text = formatTime(session.updatedAt),
        color = colors.secondaryText,
        fontSize = 12.sp,
      )
    }
  }
  // 长按菜单：改名/删除（产品 1.10：轻量，不打断读档仪式）
  DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
    DropdownMenuItem(
      text = { Text("改名") },
      onClick = {
        menuOpen = false
        renaming = true
      },
    )
    DropdownMenuItem(
      text = { Text("删除") },
      onClick = {
        menuOpen = false
        onDelete()
      },
    )
  }
  // 改名对话框（轻量）
  if (renaming) {
    androidx.compose.material3.AlertDialog(
      onDismissRequest = { renaming = false },
      title = { Text("改名") },
      text = {
        TextField(
          value = renameText,
          onValueChange = { renameText = it },
          singleLine = true,
        )
      },
      confirmButton = {
        Button(onClick = {
          renaming = false
          onRename(renameText)
        }) { Text("确定") }
      },
      dismissButton = {
        OutlinedButton(onClick = { renaming = false }) { Text("取消") }
      },
    )
  }
}

private fun formatTime(ms: Long): String {
  if (ms <= 0) return ""
  val sdf = java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault())
  return sdf.format(java.util.Date(ms))
}
