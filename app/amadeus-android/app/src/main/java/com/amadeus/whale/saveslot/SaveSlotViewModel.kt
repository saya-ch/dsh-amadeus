package com.amadeus.whale.saveslot

import androidx.compose.runtime.Stable
import com.amadeus.whale.network.AmadeusApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

@Stable data class SaveSlotUi(val id: String, val title: String, val updatedAt: Long, val lastMessage: String? = null)

class SaveSlotViewModel(private val api: AmadeusApi) {
  private val _list = MutableStateFlow<List<SaveSlotUi>>(emptyList())
  val list: StateFlow<List<SaveSlotUi>> = _list
  private val _busy = MutableStateFlow(false)
  val busy: StateFlow<Boolean> = _busy

  suspend fun load() {
    _busy.value = true
    _list.value = runCatching { api.listSessions() }.getOrDefault(emptyList())
      .map { SaveSlotUi(it.id, it.title, it.updatedAt, it.lastMessage) }
    _busy.value = false
  }

  suspend fun create(title: String? = null, workspaceId: String? = null): SaveSlotUi? {
    val s = runCatching { api.createSession(title, workspaceId) }.getOrNull() ?: return null
    return SaveSlotUi(s.id, s.title, s.updatedAt, s.lastMessage)
  }

  suspend fun rename(id: String, title: String) {
    runCatching { api.renameSession(id, title) }
    _list.value = _list.value.map { if (it.id == id) it.copy(title = title) else it }
  }

  suspend fun archive(id: String) {
    runCatching { api.archiveSession(id) }
    _list.value = _list.value.filterNot { it.id == id }
  }
}