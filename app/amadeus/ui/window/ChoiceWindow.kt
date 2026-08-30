package com.amadeus.whale.ui.window

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Galgame 选项卡，直接对应 ask_user_question
 */
@Composable
fun ChoiceWindow(question: String, options: List<String>, onSelected: (String) -> Unit) {
  Card(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
      Text(question, style = MaterialTheme.typography.titleMedium)
      options.forEach { opt ->
        Button(onClick = { onSelected(opt) }, modifier = Modifier.fillMaxWidth()) {
          Text(opt)
        }
      }
    }
  }
}
