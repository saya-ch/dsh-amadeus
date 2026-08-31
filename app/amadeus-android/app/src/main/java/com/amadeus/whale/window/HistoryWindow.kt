package com.amadeus.whale.window

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.amadeus.whale.network.AmadeusApi
import com.amadeus.whale.network.PageMessage

@Composable
fun HistoryWindow(sessionId: String, api: AmadeusApi, onClose: () -> Unit) {
  // 最新在前（index 0 = 最新 = reverseLayout 的底部）
  var messages by remember(sessionId) { mutableStateOf<List<PageMessage>>(emptyList()) }
  var hasMore by remember(sessionId) { mutableStateOf(true) }
  var loading by remember(sessionId) { mutableStateOf(false) }
  var loadedOnce by remember(sessionId) { mutableStateOf(false) }
  var failed by remember(sessionId) { mutableStateOf(false) }
  var retryTick by remember(sessionId) { mutableStateOf(0) }
  val listState = rememberLazyListState()

  suspend fun loadOlder() {
    if (loading || !hasMore) return
    loading = true
    failed = false
    runCatching {
      // Host 的 page 契约不暴露每条消息的 seq（fold 丢弃了非消息事件，条数 ≠ seq），
      // 无法用真实 seq 精确换页。这里把"已加载条数"当作单调递增的分页游标（beforeSeq 代理）：
      // 会话持续增长时游标单调推进，页面重叠由下方 (role,text) 去重兜底。
      api.pageSession(sessionId, beforeSeq = if (loadedOnce) messages.size.toLong() else null)
    }
      .onSuccess { page ->
        val fresh = page.messages.asReversed().filter { m ->
          messages.none { it.role == m.role && it.text == m.text }
        }
        // 页面无新内容：与已加载高度重叠，直接终止，避免同页反复拉取造成卡住/死循环
        if (fresh.isEmpty()) { hasMore = false; return@onSuccess }
        messages = messages + fresh
        hasMore = page.hasMore
        loadedOnce = true
      }
      .onFailure { failed = true }
    loading = false
  }

  LaunchedEffect(sessionId, retryTick) { loadOlder() }

  // 上滑到顶（reverseLayout 的末尾 = "more" 项可见）时加载更早一页
  val moreVisible = listState.layoutInfo.visibleItemsInfo.any { it.key == "more" }
  LaunchedEffect(moreVisible, loading, hasMore, loadedOnce) {
    if (moreVisible && !loading && hasMore && loadedOnce) loadOlder()
  }

  Surface(modifier = Modifier.fillMaxSize().trapTaps(), color = Color(0xFFF5F2EC)) {
    Column(Modifier.fillMaxSize()) {
      Row(
        modifier = Modifier
          .fillMaxWidth()
          .background(MaterialTheme.colorScheme.primary)
          .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Text(
          text = "会话历史",
          color = MaterialTheme.colorScheme.onPrimary,
          style = MaterialTheme.typography.titleMedium,
          modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onClose) {
          Icon(Icons.Default.Close, contentDescription = "关闭", tint = MaterialTheme.colorScheme.onPrimary)
        }
      }
      if (messages.isEmpty() && failed) {
        Column(
          modifier = Modifier.weight(1f).fillMaxWidth(),
          verticalArrangement = Arrangement.Center,
          horizontalAlignment = Alignment.CenterHorizontally,
        ) {
          Text("历史加载失败", color = Color(0xFF444444))
          Spacer(Modifier.height(12.dp))
          Button(onClick = { retryTick++ }) { Text("重试") }
        }
      } else {
        LazyColumn(
          state = listState,
          modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
          reverseLayout = true,
        ) {
          if (hasMore) {
            item(key = "more") {
              Box(Modifier.fillMaxWidth().padding(vertical = 12.dp), contentAlignment = Alignment.Center) {
                if (loading) CircularProgressIndicator(modifier = Modifier.height(20.dp))
                else Text("上滑加载更早", color = Color(0x99666666), fontSize = 13.sp)
              }
            }
          }
          itemsIndexed(messages, key = { i, m -> "m:$i:${m.role}:${m.text.take(24)}" }) { _, m -> MessageBubble(m) }
        }
      }
    }
  }
}

@Composable
private fun MessageBubble(m: PageMessage) {
  val mine = m.role == "user"
  val bubbleColor = when (m.role) {
    "user" -> Color(0xFFB3D9FF)
    "tool" -> Color(0xFFE0E0E0)
    else -> Color(0xFFFFFFFF)
  }
  val bubbleFont = if (m.role == "tool") FontWeight.Medium else FontWeight.Normal
  Row(
    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start,
  ) {
    Box(
      modifier = Modifier
        .widthIn(max = 320.dp)
        .clip(RoundedCornerShape(16.dp))
        .background(bubbleColor)
        .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
      Text(m.text, fontSize = 15.sp, fontWeight = bubbleFont)
    }
  }
  Spacer(Modifier.height(2.dp))
}