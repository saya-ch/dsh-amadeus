package com.amadeus.whale.scan

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.hardware.Camera
import android.os.Bundle
import android.view.Gravity
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.TextView
import com.amadeus.whale.R
import com.amadeus.whale.pairing.QrDecoder

@Suppress("DEPRECATION")
class ScanActivity : Activity(), Camera.PreviewCallback {
  private var camera: Camera? = null
  private var surfaceHolder: SurfaceHolder? = null

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
      setResult(RESULT_CANCELED)
      finish()
      return
    }
    setContentView(buildUi())
  }

  private fun buildUi(): FrameLayout {
    val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
    val preview = SurfaceView(this).apply {
      layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
    }
    root.addView(preview)
    surfaceHolder = preview.holder
    surfaceHolder?.addCallback(object : SurfaceHolder.Callback {
      override fun surfaceCreated(holder: SurfaceHolder) {
        surfaceHolder = holder
        openCamera(holder)
      }

      override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}

      override fun surfaceDestroyed(holder: SurfaceHolder) {
        releaseCamera()
      }
    })

    val frame = FrameLayout(this)
    frame.setBackgroundResource(R.drawable.scan_frame)
    frame.layoutParams = FrameLayout.LayoutParams(260.dp(), 260.dp(), Gravity.CENTER)
    root.addView(frame)

    val hint = TextView(this).apply {
      text = "将二维码放入框内"
      setTextColor(Color.WHITE)
      textSize = 16f
    }
    hint.layoutParams = FrameLayout.LayoutParams(
      ViewGroup.LayoutParams.WRAP_CONTENT,
      ViewGroup.LayoutParams.WRAP_CONTENT,
      Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL,
    ).apply { bottomMargin = 90.dp() }
    root.addView(hint)

    val close = Button(this).apply {
      text = "关闭"
      setOnClickListener { setResult(RESULT_CANCELED); finish() }
    }
    close.layoutParams = FrameLayout.LayoutParams(
      ViewGroup.LayoutParams.WRAP_CONTENT,
      ViewGroup.LayoutParams.WRAP_CONTENT,
      Gravity.TOP or Gravity.END,
    ).apply { topMargin = 40.dp(); marginEnd = 16.dp() }
    root.addView(close)
    return root
  }

  @Suppress("DEPRECATION")
  private fun openCamera(holder: SurfaceHolder) {
    if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return
    if (camera != null) return
    val cam = try {
      Camera.open()
    } catch (_: Exception) {
      null
    }
    cam?.let {
      camera = it
      try {
        it.setPreviewDisplay(holder)
      } catch (_: Exception) {
        releaseCamera()
        return
      }
      val params = it.parameters
      val size = params.supportedPreviewSizes?.minByOrNull { p -> kotlin.math.abs(p.width - 720) }
      if (size != null) {
        params.setPreviewSize(size.width, size.height)
      }
      try {
        it.parameters = params
      } catch (_: Exception) {
      }
      it.setPreviewCallback(this)
      try {
        it.startPreview()
      } catch (_: Exception) {
        releaseCamera()
      }
    }
  }

  private fun releaseCamera() {
    camera?.let {
      try {
        it.setPreviewCallback(null)
      } catch (_: Exception) {
      }
      try {
        it.stopPreview()
      } catch (_: Exception) {
      }
      try {
        it.release()
      } catch (_: Exception) {
      }
    }
    camera = null
  }

  override fun onResume() {
    super.onResume()
    if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
      setResult(RESULT_CANCELED)
      finish()
      return
    }
    surfaceHolder?.let { holder ->
      openCamera(holder)
    }
  }

  override fun onPause() {
    releaseCamera()
    super.onPause()
  }

  @Suppress("DEPRECATION")
  override fun onPreviewFrame(data: ByteArray, camera: Camera) {
    val size = camera.parameters.previewSize ?: return
    val text = QrDecoder.decodeNv21(data, size.width, size.height) ?: return
    releaseCamera()
    setResult(RESULT_OK, Intent().putExtra("pairing_text", text))
    finish()
  }

  override fun onDestroy() {
    releaseCamera()
    super.onDestroy()
  }

  private fun Int.dp(): Int = (this * resources.displayMetrics.density).toInt()
}
