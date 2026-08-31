package com.amadeus.whale.window

import android.annotation.SuppressLint
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import coil.compose.AsyncImage
import com.amadeus.whale.network.PreviewPayload

@Composable
fun PreviewWindow(preview: PreviewPayload, onClose: () -> Unit) {
  Surface(modifier = Modifier.fillMaxSize().trapTaps(), color = Color(0xFF141414)) {
    Column(Modifier.fillMaxSize()) {
      Row(
        modifier = Modifier
          .fillMaxWidth()
          .background(Color(0xFF222222))
          .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Text(
          text = preview.title.ifBlank { "预览" },
          color = Color.White,
          style = MaterialTheme.typography.titleMedium,
          modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onClose) {
          Icon(Icons.Default.Close, contentDescription = "关闭", tint = Color.White)
        }
      }
      Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
        when (preview.type) {
          "web" -> WebPreview(preview.content)
          "image" -> ZoomableBox { AsyncImage(model = preview.content, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize()) }
          else -> ZoomableBox {
            Text(
              text = preview.content,
              fontFamily = FontFamily.Monospace,
              fontSize = 13.sp,
              color = Color(0xFFE6E6E6),
              modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
            )
          }
        }
      }
    }
  }
}

@Composable
private fun ZoomableBox(content: @Composable () -> Unit) {
  var scale by remember { mutableStateOf(1f) }
  var offset by remember { mutableStateOf(Offset.Zero) }
  val state = rememberTransformableState { zoomChange, panChange, _ ->
    scale = (scale * zoomChange).coerceIn(1f, 8f)
    offset += panChange
  }
  Box(
    modifier = Modifier
      .fillMaxSize()
      .transformable(state)
      .graphicsLayer {
        scaleX = scale
        scaleY = scale
        translationX = offset.x
        translationY = offset.y
      }
      .clipToBounds(),
  ) { content() }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun WebPreview(url: String) {
  AndroidView(
    factory = { ctx ->
      WebView(ctx).apply {
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        webViewClient = WebViewClient()
        loadUrl(url)
      }
    },
    modifier = Modifier.fillMaxSize(),
    onRelease = { it.destroy() },
  )
}