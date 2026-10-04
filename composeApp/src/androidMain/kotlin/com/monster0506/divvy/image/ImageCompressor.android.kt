package com.monster0506.divvy.image

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import java.io.ByteArrayOutputStream

actual suspend fun compressImage(bytes: ByteArray, maxLongEdge: Int, quality: Int): ByteArray {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    require(bounds.outWidth > 0 && bounds.outHeight > 0) { "Unsupported image data" }

    // Subsample while decoding so a full-size photo is never held in memory.
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxLongEdge) sample *= 2
    val decoded = BitmapFactory.decodeByteArray(
        bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample },
    ) ?: throw IllegalArgumentException("Unsupported image data")

    val (w, h) = fitWithin(decoded.width, decoded.height, maxLongEdge)
    val matrix = Matrix().apply {
        if (w != decoded.width || h != decoded.height) {
            postScale(w.toFloat() / decoded.width, h.toFloat() / decoded.height)
        }
        postRotate(exifRotation(bytes))
    }
    val result = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)

    val out = ByteArrayOutputStream()
    result.compress(Bitmap.CompressFormat.JPEG, quality.coerceIn(1, 100), out)
    if (result !== decoded) result.recycle()
    decoded.recycle()
    return out.toByteArray()
}

private fun exifRotation(bytes: ByteArray): Float =
    when (ExifInterface(bytes.inputStream()).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
        ExifInterface.ORIENTATION_ROTATE_90 -> 90f
        ExifInterface.ORIENTATION_ROTATE_180 -> 180f
        ExifInterface.ORIENTATION_ROTATE_270 -> 270f
        else -> 0f
    }
