package com.amadeus.whale.settings

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(
  viewModel: SettingsViewModel,
  isRealMode: Boolean,
  onDone: () -> Unit,
) {
  val url by viewModel.url.collectAsState()
  val status by viewModel.status.collectAsState()
  var urlInput by remember(url) { mutableStateOf(url) }
  var testing by remember { mutableStateOf(false) }
  var sound by remember { mutableStateOf(viewModel.soundEnabled) }
  var toolProgress by remember { mutableStateOf(viewModel.showToolProgress) }
  val scope = rememberCoroutineScope()

  Surface(Modifier.fillMaxSize()) {
    Column(Modifier.fillMaxSize().padding(20.dp)) {
      Text("设置", style = MaterialTheme.typography.titleLarge)
      Spacer(Modifier.height(16.dp))
      OutlinedTextField(value = urlInput, onValueChange = { urlInput = it }, label = { Text("网关地址") },
        placeholder = { Text("https://192.168.x.x:3444") }, singleLine = true, modifier = Modifier.fillMaxWidth())
      Spacer(Modifier.height(8.dp))
      Row(verticalAlignment = Alignment.CenterVertically) {
        Button(onClick = {
          testing = true
          scope.launch {
            viewModel.testAndSave(urlInput.trim())
            testing = false
          }
        }, enabled = !testing) { Text(if (testing) "测试中…" else "测试并保存") }
        Spacer(Modifier.width(12.dp))
        Text(status)
      }
      Spacer(Modifier.height(24.dp))
      Text("演出偏好", style = MaterialTheme.typography.titleMedium)
      Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text("环境音（demo）")
        Switch(checked = sound, onCheckedChange = { sound = it; viewModel.soundEnabled = it })
      }
      Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text("工具进度")
        Switch(checked = toolProgress, onCheckedChange = { toolProgress = it; viewModel.showToolProgress = it })
      }
      if (isRealMode) {
        Spacer(Modifier.height(24.dp))
        OutlinedButton(onClick = { viewModel.disconnect() }, modifier = Modifier.fillMaxWidth()) {
          Text("断开连接并回演示")
        }
      }
      Spacer(Modifier.weight(1f))
      Button(onClick = onDone, modifier = Modifier.fillMaxWidth()) { Text("返回") }
    }
  }
}