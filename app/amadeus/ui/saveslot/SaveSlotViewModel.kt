package com.amadeus.whale.ui.saveslot

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

class SaveSlotViewModel(
  private val repo: SaveSlotRepository,
  private val scope: CoroutineScope
) {
  private val _slots = MutableStateFlow<List<SaveSlot>>(emptyList())
  val slots: StateFlow<List<SaveSlot>> = _slots

  fun load() {
    scope.launch {
      _slots.value = repo.list("amadeus")
    }
  }

  fun createNew(onCreated: (String) -> Unit) {
    scope.launch {
      val s = repo.create("amadeus")
      load()
      onCreated(s.id)
    }
  }
}

interface SaveSlotRepository {
  suspend fun list(mode: String): List<SaveSlot>
  suspend fun create(mode: String): SaveSlot
}
