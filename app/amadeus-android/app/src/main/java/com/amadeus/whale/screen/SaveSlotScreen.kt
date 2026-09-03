package com.amadeus.whale.screen

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.TextButton
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
  var loadError by remember { mutableStateOf<String?>(null) }
  var sessions by remember { mutableStateOf<List<AmadeusSession>>(emptyList()) }
  var loading by remember { mutableStateOf(true) }
  val scope = rememberCoroutineScope()

  // 网络失败不崩（网关重启/超时会抛）：显示错误 + 重试；加载中显示动画
  suspend fun refreshList() {
    loading = true
    loadError = null
    sessions = try {
      repository.list().distinctBy { it.id }
    } catch (error: Exception) {
      loadError = error.message ?: "无法连接电脑"
      emptyList()
    }
    loading = false
  }

  LaunchedEffect(Unit) { refreshList() }

  Column(modifier = Modifier.fillMaxSize().background(colors.screenBackground)) {
    // 顶部：settings-frame 实心深海蓝大横幅（maid-atelier，CC BY-NC-SA 4.0）
    Box(
      modifier = Modifier
        .fillMaxWidth()
        .height(96.dp),
    ) {
      com.amadeus.whale.theatre.AssetImage(
        name = "maid-settings-frame-v1",
        modifier = Modifier.fillMaxSize(),
        contentScale = androidx.compose.ui.layout.ContentScale.Crop,
      )
      // 缎带标题（ribbon 叠在横幅上）
      Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        com.amadeus.whale.theatre.AssetImage(
          name = "maid-workspace-ribbon-v2",
          modifier = Modifier.fillMaxWidth(0.7f).height(52.dp),
        )
        Text(
          text = "读档",
          style = androidx.compose.material3.MaterialTheme.typography.headlineSmall,
          fontWeight = FontWeight.Bold,
          color = colors.namePlateText,
          modifier = Modifier.align(Alignment.Center),
        )
      }
      // 盾牌角标（右上）
      com.amadeus.whale.theatre.AssetImage(
        name = "maid-workspace-shield-v2",
        modifier = Modifier
          .align(Alignment.CenterEnd)
          .padding(end = 24.dp)
          .height(64.dp),
        contentScale = androidx.compose.ui.layout.ContentScale.Fit,
      )
      // 返回（左上）
      Text(
        text = "‹ 返回",
        color = colors.namePlateText,
        fontSize = 16.sp,
        modifier = Modifier.align(Alignment.CenterStart).padding(start = 16.dp).clickable(onClick = onBack),
      )
    }
    Spacer(Modifier.height(16.dp))
    // 新建（"开启新的一天"仪式感，产品 1.10）：默认 Amadeus 工作区 / App 内选其他目录
    var showNewDialog by remember { mutableStateOf(false) }
    var showDirPicker by remember { mutableStateOf(false) }
    Button(
      onClick = { showNewDialog = true },
      modifier = Modifier.padding(horizontal = 20.dp).fillMaxWidth(),
    ) {
      Text("＋ 开启新的一天")
    }
    if (showNewDialog) {
      androidx.compose.material3.AlertDialog(
        onDismissRequest = { showNewDialog = false },
        title = { Text("开启新的一天") },
        text = {
          Column {
            Text("在哪个工作区开始？", color = colors.secondaryText, fontSize = 13.sp)
            Spacer(Modifier.height(12.dp))
            Button(
              onClick = {
                showNewDialog = false
                scope.launch {
                  try {
                    val created = repository.create()
                    onOpenSession(created.id)
                  } catch (error: Exception) {
                    loadError = error.message ?: "无法连接电脑"
                  }
                }
              },
              modifier = Modifier.fillMaxWidth(),
            ) { Text("默认（Amadeus 工作区）") }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
              onClick = {
                showNewDialog = false
                showDirPicker = true
              },
              modifier = Modifier.fillMaxWidth(),
            ) { Text("选择其他文件夹…") }
          }
        },
        confirmButton = {},
        dismissButton = {
          TextButton(onClick = { showNewDialog = false }) { Text("取消") }
        },
      )
    }
    if (showDirPicker) {
      DirectoryPickerDialog(
        repository = repository,
        onDismiss = { showDirPicker = false },
        onPicked = { workspaceId ->
          showDirPicker = false
          scope.launch {
            try {
              val created = repository.create(workspaceId)
              onOpenSession(created.id) // 建完直接进剧场；回读档页时重新 list
            } catch (error: Exception) {
              loadError = error.message ?: "无法连接电脑"
            }
          }
        },
      )
    }
    Spacer(Modifier.height(12.dp))
    if (loading) {
      // 会话加载中：居中加载动画
      Box(modifier = Modifier.fillMaxWidth().padding(top = 120.dp), contentAlignment = Alignment.Center) {
        androidx.compose.material3.CircularProgressIndicator(
          color = colors.accent,
          modifier = Modifier.width(34.dp).height(34.dp),
        )
      }
    } else if (loadError != null && sessions.isEmpty()) {
      // 网络失败：错误 + 重试
      Text(
        text = loadError ?: "",
        color = androidx.compose.ui.graphics.Color(0xFFB3261E),
        fontSize = 13.sp,
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
      )
      TextButton(onClick = { scope.launch { refreshList() } }) { Text("重试") }
    } else {
      // 按工作区分组 + 组内倒序
      LazyColumn(modifier = Modifier.fillMaxSize()) {
        val grouped = sessions.groupBy { it.workspace.ifEmpty { "默认工作区" } }
        if (grouped.isEmpty()) {
          item {
            Text(
              text = "还没有会话——点上方「开启新的一天」开始吧",
              color = colors.secondaryText,
              fontSize = 13.sp,
              modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
            )
          }
        }
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
              onDelete = { scope.launch {
                try {
                  repository.archive(session.id)
                  refreshList()
                } catch (error: Exception) {
                  loadError = error.message ?: "无法连接电脑"
                }
              } },
            )
          }
        }
      }
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

/**
 * App 内目录选择器：浏览电脑目录树，选一个目录作为新会话工作区。
 * 数据走服务端 /workspaces/browse（dsh directoryPicker browse 能力）。
 */
@Composable
private fun DirectoryPickerDialog(
  repository: SessionRepository,
  onDismiss: () -> Unit,
  onPicked: (workspaceId: String) -> Unit,
) {
  val colors = LocalAmadeusColors.current
  val scope = rememberCoroutineScope()
  var currentPath by remember { mutableStateOf<String?>(null) }
  var listing by remember { mutableStateOf(com.amadeus.whale.domain.DirectoryListingView("", "", emptyList(), emptyList())) }
  var busy by remember { mutableStateOf(false) }
  var error by remember { mutableStateOf<String?>(null) }

  suspend fun load(path: String?) {
    busy = true
    error = null
    try {
      listing = repository.browseDirectory(path)
      currentPath = listing.path
    } catch (e: Exception) {
      error = e.message ?: "无法读取目录"
    }
    busy = false
  }

  LaunchedEffect(Unit) { load(null) }

  androidx.compose.material3.AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text("选择文件夹") },
    text = {
      Column(modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp)) {
        // 面包屑（当前路径祖先，可点跳转）
        if (listing.crumbs.isNotEmpty()) {
          Row(modifier = Modifier.horizontalScroll(androidx.compose.foundation.rememberScrollState())) {
            listing.crumbs.forEach { (name, path) ->
              Text(
                text = if (name.isEmpty()) "/" else name,
                color = colors.accent,
                fontSize = 13.sp,
                modifier = Modifier
                  .padding(end = 4.dp)
                  .clickable { scope.launch { load(path) } },
              )
              Text("/", color = colors.secondaryText, fontSize = 13.sp)
            }
          }
          Spacer(Modifier.height(6.dp))
        }
        if (error != null) {
          Text(error ?: "", color = androidx.compose.ui.graphics.Color(0xFFB3261E), fontSize = 13.sp)
          Spacer(Modifier.height(6.dp))
        }
        if (busy) {
          Text("读取中…", color = colors.secondaryText, fontSize = 13.sp)
        } else if (listing.entries.isEmpty()) {
          Text("（空目录）", color = colors.secondaryText, fontSize = 13.sp)
        } else {
          LazyColumn(modifier = Modifier.weight(1f, fill = false)) {
            items(listing.entries.filterNot { it.third }) { (name, path, _) ->
              Row(
                modifier = Modifier.fillMaxWidth().clickable { scope.launch { load(path) } }.padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
              ) {
                Text("📁", fontSize = 14.sp)
                Spacer(Modifier.width(8.dp))
                Text(name, color = colors.primaryText, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
              }
            }
          }
        }
      }
    },
    confirmButton = {
      // “用当前目录” → 注册为工作区 → 建会话
      Button(
        onClick = {
          val path = currentPath
          if (path.isNullOrEmpty()) return@Button
          busy = true
          scope.launch {
            try {
              val ws = repository.registerWorkspace(path)
              onPicked(ws.id)
            } catch (e: Exception) {
              error = e.message ?: "注册失败"
            }
            busy = false
          }
        },
        enabled = !currentPath.isNullOrEmpty() && !busy,
      ) { Text("用这个文件夹") }
    },
    dismissButton = {
      TextButton(onClick = onDismiss) { Text("取消") }
    },
  )
}
