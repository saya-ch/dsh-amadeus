package com.amadeus.whale.scan

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import com.google.zxing.BinaryBitmap
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/**
 * 扫码配对（架构 3.5）：camera 预览 + zxing 帧解码，返回配对 URL。
 */
class ScanActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    WindowCompat.setDecorFitsSystemWindows(window, true)
    setContent {
      ScanScreen(
        onCancel = { finish() },
        onScanned = { result ->
          setResult(RESULT_OK, android.content.Intent().putExtra("scan_result", result))
          finish()
        },
      )
    }
  }
}

@Composable
private fun ScanScreen(onCancel: () -> Unit, onScanned: (String) -> Unit) {
  val context = LocalContext.current
  var permissionGranted by remember {
    mutableStateOf(
      ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
        PackageManager.PERMISSION_GRANTED,
    )
  }
  val launcher = androidx.activity.compose.rememberLauncherForActivityResult(
    ActivityResultContracts.RequestPermission(),
  ) { granted -> permissionGranted = granted }
  LaunchedEffect(Unit) { if (!permissionGranted) launcher.launch(Manifest.permission.CAMERA) }

  Box(modifier = Modifier.fillMaxSize()) {
    if (permissionGranted) {
      CameraPreview(onScanned = onScanned)
      // 取景框：周边压暗 + 中央方框四角（扫码引导）
      QrViewfinder()
    } else {
      Text("需要相机权限来扫码配对", modifier = Modifier.align(Alignment.Center))
    }
    TextButton(
      onClick = onCancel,
      modifier = Modifier.align(Alignment.TopEnd).padding(16.dp),
    ) { Text("取消", color = Color.White) }
    Text(
      text = "扫描配对二维码",
      color = Color.White,
      fontSize = 16.sp,
      modifier = Modifier.align(Alignment.TopCenter).padding(16.dp),
    )
  }
}

@Composable
private fun QrViewfinder() {
  val dark = androidx.compose.ui.graphics.Color(0x99000000)
  val accent = androidx.compose.ui.graphics.Color(0xFFE8C36A) // 金线（与主题一致）
  val cornerLen = 28.dp
  val stroke = 3.dp
  androidx.compose.foundation.Canvas(modifier = Modifier.fillMaxSize()) {
    val size = this.size.minDimension * 0.7f // 方框边长 = 屏短边 70%
    val left = (this.size.width - size) / 2f
    val top = (this.size.height - size) / 2f - (this.size.height * 0.05f)
    val right = left + size
    val bottom = top + size
    val cl = cornerLen.toPx()
    val sw = stroke.toPx()
    // 周边压暗（四个矩形）
    drawRect(dark, topLeft = androidx.compose.ui.geometry.Offset(0f, 0f), size = androidx.compose.ui.geometry.Size(this.size.width, top))
    drawRect(dark, topLeft = androidx.compose.ui.geometry.Offset(0f, bottom), size = androidx.compose.ui.geometry.Size(this.size.width, this.size.height - bottom))
    drawRect(dark, topLeft = androidx.compose.ui.geometry.Offset(0f, top), size = androidx.compose.ui.geometry.Size(left, size))
    drawRect(dark, topLeft = androidx.compose.ui.geometry.Offset(right, top), size = androidx.compose.ui.geometry.Size(this.size.width - right, size))
    // 四角 L 线
    val path = androidx.compose.ui.graphics.Path()
    // 左上
    path.moveTo(left, top + cl); path.lineTo(left, top); path.lineTo(left + cl, top)
    // 右上
    path.moveTo(right - cl, top); path.lineTo(right, top); path.lineTo(right, top + cl)
    // 右下
    path.moveTo(right, bottom - cl); path.lineTo(right, bottom); path.lineTo(right - cl, bottom)
    // 左下
    path.moveTo(left + cl, bottom); path.lineTo(left, bottom); path.lineTo(left, bottom - cl)
    drawPath(path, color = accent, style = androidx.compose.ui.graphics.drawscope.Stroke(width = sw))
  }
}

@Composable
private fun CameraPreview(onScanned: (String) -> Unit) {
  val context = LocalContext.current
  val executor = remember { Executors.newSingleThreadExecutor() }
  val reader = remember { MultiFormatReader() }
  var lastScanAttempt by remember { mutableStateOf(0L) }
  // PreviewView 必须在绑定前创建并传给 setSurfaceProvider（否则预览黑屏）
  val previewView = remember { PreviewView(context).apply { implementationMode = PreviewView.ImplementationMode.PERFORMANCE } }

  LaunchedEffect(Unit) {
    val provider = context.getCameraProvider()
    val preview = Preview.Builder().build()
    preview.setSurfaceProvider(previewView.surfaceProvider)
    val analysis = ImageAnalysis.Builder()
      .setBackpressureStrategy(STRATEGY_KEEP_ONLY_LATEST)
      .build()
    analysis.setAnalyzer(executor) { imageProxy ->
      val now = System.currentTimeMillis()
      if (now - lastScanAttempt < 500) {
        imageProxy.close()
        return@setAnalyzer
      }
      lastScanAttempt = now
      val result = decodeFrame(reader, imageProxy)
      if (result != null && (result.contains("mobile-access/pair") || result.contains("amadeus/pair"))) {
        onScanned(result)
      }
      imageProxy.close()
    }
    provider.bindToLifecycle(
      context as androidx.lifecycle.LifecycleOwner,
      CameraSelector.DEFAULT_BACK_CAMERA,
      preview,
      analysis,
    )
  }
  Box(Modifier.fillMaxSize()) {
    AndroidView(
      factory = { previewView },
      modifier = Modifier.fillMaxSize(),
    )
  }
}

/** zxing 解码 YUV 帧（无需 mlkit）。 */
private fun decodeFrame(reader: MultiFormatReader, proxy: ImageProxy): String? {
  val plane = proxy.planes.firstOrNull() ?: return null
  val buffer: ByteBuffer = plane.buffer
  val data = ByteArray(buffer.remaining())
  buffer.get(data)
  val source = PlanarYUVLuminanceSource(
    data,
    proxy.width,
    proxy.height,
    0, 0,
    proxy.width,
    proxy.height,
    false,
  )
  return try {
    val bitmap = BinaryBitmap(HybridBinarizer(source))
    reader.decodeWithState(bitmap)?.text
  } catch (_: Exception) {
    null
  } finally {
    reader.reset()
  }
}

private suspend fun android.content.Context.getCameraProvider(): ProcessCameraProvider =
  suspendCoroutine { continuation ->
    ProcessCameraProvider.getInstance(this).addListener(
      { continuation.resume(ProcessCameraProvider.getInstance(this).get()) },
      ContextCompat.getMainExecutor(this),
    )
  }
