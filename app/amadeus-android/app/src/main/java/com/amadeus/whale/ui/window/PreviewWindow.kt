package com.amadeus.whale.ui.window

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun PreviewWindow(title: String, content: String, isImage: Boolean, onClose: () -> Unit) {
  Card(modifier = Modifier.fillMaxSize().padding(16.dp)) {
    Column(modifier = Modifier.padding(16.dp).fillMaxSize()) {
      Text(title, style = MaterialTheme.typography.titleLarge)
      Spacer(Modifier.height(8.dp))
      if (isImage) {
        // Coil AsyncImage
        Text("[图片预览: $content]", modifier = Modifier.weight(1f))
      } else {
        Text(content, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
      }
      Button(onClick = onClose) { Text("关闭") }
    }
  }
}
