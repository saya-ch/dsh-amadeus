package com.amadeus.whale.saveslot

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SaveSlotScreen(
  viewModel: SaveSlotViewModel,
  onOpen: (String) -> Unit,
  onChangeConnection: () -> Unit,
  onNewSession: () -> Unit,
) {
  val list by viewModel.list.collectAsState()
  val scope = rememberCoroutineScope()
  var menuId by remember { mutableStateOf<String?>(null) }
  var renameId by remember { mutableStateOf<String?>(null) }
  var renameTitle by remember { mutableStateOf("") }

  Box(Modifier.fillMaxSize()) {
    LazyColumn(Modifier.fillMaxSize()) {
      item { Text("选择会话", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(16.dp)) }
      items(list, key = { it.id }) { slot ->
        Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
          Column(Modifier.combinedClickable(
            onClick = { onOpen(slot.id) },
            onLongClick = { menuId = slot.id },
          ).padding(16.dp)) {
            Text(slot.title, style = MaterialTheme.typography.titleMedium)
            slot.lastMessage?.let { Text(it, maxLines = 1, style = MaterialTheme.typography.bodySmall) }
          }
        }
      }
    }
    Button(onClick = onNewSession, modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp).fillMaxWidth()) {
      Text("新建会话")
    }
    TextButton(onClick = onChangeConnection, modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp)) {
      Text("切换连接")
    }
  }

  menuId?.let { id ->
    AlertDialog(onDismissRequest = { menuId = null },
      title = { Text("会话操作") },
      text = { Text("对该会话执行操作") },
      confirmButton = { TextButton(onClick = { renameId = id; renameTitle = list.first { it.id == id }.title; menuId = null }) { Text("改名") } },
      dismissButton = { TextButton(onClick = { scope.launch { viewModel.archive(id) }; menuId = null }) { Text("删除") } },
    )
  }
  renameId?.let { id ->
    AlertDialog(onDismissRequest = { renameId = null },
      title = { Text("重命名") },
      text = { OutlinedTextField(value = renameTitle, onValueChange = { renameTitle = it }) },
      confirmButton = {
        TextButton(onClick = { scope.launch { viewModel.rename(id, renameTitle) }; renameId = null }) { Text("确定") }
      },
      dismissButton = { TextButton(onClick = { renameId = null }) { Text("取消") } },
    )
  }
}