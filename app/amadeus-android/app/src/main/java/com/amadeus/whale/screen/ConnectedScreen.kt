package com.amadeus.whale.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.amadeus.whale.domain.SessionRepository
import com.amadeus.whale.domain.model.AmadeusSession
import com.amadeus.whale.domain.model.AmadeusSprite
import com.amadeus.whale.theme.LocalAmadeusColors
import com.amadeus.whale.theatre.TheatreStage

/**
 * 连接成功中间页：配对/恢复后先停在这，不直接跳存档。
 * 鲸鱼娘报平安 + 最近会话卡（若有）→ 用户选：继续那个对话 / 去读档页。
 */
@Composable
fun ConnectedScreen(
  repository: SessionRepository,
  lastSessionId: String?,
  onContinue: (sessionId: String) -> Unit,
  onOpenSaveSlot: () -> Unit,
) {
  val colors = LocalAmadeusColors.current
  var sessions by remember { mutableStateOf<List<AmadeusSession>>(emptyList()) }
  var recent by remember { mutableStateOf<AmadeusSession?>(null) }
  var loading by remember { mutableStateOf(true) }

  LaunchedEffect(Unit) {
    sessions = try { repository.list() } catch (_: Exception) { emptyList() }
    recent = lastSessionId?.let { id -> sessions.find { it.id == id } }
      ?: sessions.maxByOrNull { it.updatedAt }
    loading = false
  }

  Box(modifier = Modifier.fillMaxSize().background(colors.screenBackground)) {
    TheatreStage(
      background = "bg-deepseek-seaside-study",
      sprite = AmadeusSprite.happy,
    )
    Column(
      modifier = Modifier.align(Alignment.Center).padding(horizontal = 32.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
    ) {
      Text(
        text = "呜…她回来了！",
        color = colors.primaryText,
        fontSize = 16.sp,
        textAlign = TextAlign.Center,
        modifier = Modifier
          .background(androidx.compose.ui.graphics.Color(0xCCFFFDF8), RoundedCornerShape(12.dp))
          .padding(horizontal = 14.dp, vertical = 10.dp),
      )
      Spacer(Modifier.height(14.dp))
      if (recent != null) {
        val s = recent!!
        Card(
          onClick = { onContinue(s.id) },
          colors = CardDefaults.cardColors(containerColor = androidx.compose.ui.graphics.Color(0xE6FFFDF8)),
          shape = RoundedCornerShape(14.dp),
          modifier = Modifier.fillMaxWidth(),
        ) {
          Row(modifier = Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
              Text(
                text = "继续上次的对话？",
                color = colors.secondaryText,
                fontSize = 12.sp,
              )
              Spacer(Modifier.height(4.dp))
              Text(
                text = s.title.ifEmpty { "未命名" },
                color = colors.primaryText,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
              )
              if (s.workspace.isNotEmpty()) {
                Text(text = s.workspace, color = colors.secondaryText, fontSize = 11.sp)
              }
            }
            Spacer(Modifier.width(10.dp))
            Text(text = "继续 ›", color = colors.accent, fontSize = 14.sp, fontWeight = FontWeight.Bold)
          }
        }
      } else if (loading) {
        // 会话加载中：进度圈卡片
        Card(
          colors = CardDefaults.cardColors(containerColor = androidx.compose.ui.graphics.Color(0xE6FFFDF8)),
          shape = RoundedCornerShape(14.dp),
          modifier = Modifier.fillMaxWidth(),
        ) {
          Row(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
          ) {
            androidx.compose.material3.CircularProgressIndicator(
              color = colors.accent,
              modifier = Modifier.width(20.dp).height(20.dp),
              strokeWidth = 2.dp,
            )
            Spacer(Modifier.width(12.dp))
            Text(
              text = "正在找你的会话…",
              color = colors.secondaryText,
              fontSize = 13.sp,
            )
          }
        }
      } else {
        Card(
          colors = CardDefaults.cardColors(containerColor = androidx.compose.ui.graphics.Color(0xE6FFFDF8)),
          shape = RoundedCornerShape(14.dp),
          modifier = Modifier.fillMaxWidth(),
        ) {
          Text(
            text = "还没有会话——开启新的一天吧",
            color = colors.secondaryText,
            fontSize = 13.sp,
            modifier = Modifier.padding(14.dp),
          )
        }
      }
      Spacer(Modifier.height(18.dp))
      Button(onClick = { onOpenSaveSlot() }, enabled = !loading, modifier = Modifier.fillMaxWidth()) {
        Text(if (recent != null) "去读档页看看" else "开启新的一天")
      }
      Spacer(Modifier.height(6.dp))
      if (recent != null) {
        TextButton(onClick = { onOpenSaveSlot() }) {
          Text("换个会话", color = colors.secondaryText, fontSize = 13.sp)
        }
      }
    }
  }
}
