package com.amadeus.whale.ui.saveslot

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

data class SaveSlot(val id: String, val title: String, val updatedAt: Long, val lastMessage: String? = null)

@Composable
fun SaveSlotScreen(
  viewModel: SaveSlotViewModel,
  onSlotClick: (String) -> Unit,
  onNewSlot: () -> Unit
) {
  val slots by viewModel.slots.collectAsState()
  Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
    Text("Amadeus 存档", style = MaterialTheme.typography.headlineMedium)
    Spacer(modifier = Modifier.height(12.dp))
    Button(onClick = onNewSlot, modifier = Modifier.fillMaxWidth()) {
      Text("＋ 新建存档 (amadeus mode)")
    }
    Spacer(modifier = Modifier.height(16.dp))
    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
      items(slots) { slot ->
        Card(modifier = Modifier.fillMaxWidth().clickable { onSlotClick(slot.id) }) {
          Column(modifier = Modifier.padding(12.dp)) {
            Text(slot.title, style = MaterialTheme.typography.titleMedium)
            Text("ID: ${slot.id} · ${java.util.Date(slot.updatedAt)}", style = MaterialTheme.typography.bodySmall)
            if (slot.lastMessage != null) Text(slot.lastMessage, maxLines = 1, style = MaterialTheme.typography.bodySmall)
          }
        }
      }
    }
  }
}
