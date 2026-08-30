package com.amadeus.whale.window

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.amadeus.whale.network.ReportPayload

@Composable
fun ReportWindow(report: ReportPayload, onClose: () -> Unit) {
  Surface(modifier = Modifier.fillMaxSize(), color = Color(0xFFF5F2EC)) {
    Column(Modifier.fillMaxSize()) {
      Row(
        modifier = Modifier
          .fillMaxWidth()
          .background(MaterialTheme.colorScheme.primary)
          .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Text(
          text = report.title.ifBlank { "报告" },
          color = MaterialTheme.colorScheme.onPrimary,
          style = MaterialTheme.typography.titleMedium,
          modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onClose) {
          Icon(Icons.Default.Close, contentDescription = "关闭", tint = MaterialTheme.colorScheme.onPrimary)
        }
      }
      LazyColumn(Modifier.weight(1f).fillMaxWidth().padding(16.dp)) {
        items(report.markdown.lines()) { line -> ReportLine(line) }
      }
    }
  }
}

@Composable
private fun ReportLine(line: String) {
  val trimmed = line.trim()
  when {
    trimmed.startsWith("## ") -> Text(trimmed.removePrefix("## "), style = MaterialTheme.typography.titleLarge, color = Color(0xFF2B4A6F))
    trimmed.startsWith("# ") -> Text(trimmed.removePrefix("# "), style = MaterialTheme.typography.headlineSmall, color = Color(0xFF1F3A5F))
    trimmed.startsWith("- ") || trimmed.startsWith("* ") ->
      Text("•  ${trimmed.drop(2)}", style = MaterialTheme.typography.bodyMedium)
    trimmed.startsWith("[") && trimmed.contains("](") ->
      Text(trimmed, style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace)
    else -> Text(line, style = MaterialTheme.typography.bodyMedium)
  }
  Spacer(Modifier.height(6.dp))
}