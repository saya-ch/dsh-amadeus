package com.amadeus.whale.ui.window

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun ReportWindow(title: String, markdown: String, onClose: () -> Unit, onOpenInDSH: () -> Unit) {
  Card(modifier = Modifier.fillMaxSize().padding(16.dp)) {
    Column(modifier = Modifier.padding(16.dp).fillMaxSize()) {
      Text(title, style = MaterialTheme.typography.titleLarge)
      Spacer(Modifier.height(8.dp))
      Text(markdown, modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()), style = MaterialTheme.typography.bodySmall)
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = onClose) { Text("关闭") }
        OutlinedButton(onClick = onOpenInDSH) { Text("在 DSH 中打开") }
      }
    }
  }
}
