package com.amadeus.whale.pairing

import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.NotFoundException
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer

object QrDecoder {
  fun decodeNv21(yPlane: ByteArray, width: Int, height: Int): String? {
    if (yPlane.size < width * height) return null
    val source = PlanarYUVLuminanceSource(yPlane, width, height, 0, 0, width, height, false)
    val reader = MultiFormatReader()
    reader.setHints(mapOf(
      DecodeHintType.TRY_HARDER to true,
      DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
    ))
    return try {
      val result = reader.decodeWithState(BinaryBitmap(HybridBinarizer(source)))
      result.text
    } catch (_: NotFoundException) {
      null
    } finally {
      reader.reset()
    }
  }
}
