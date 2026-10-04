package com.monster0506.divvy.image

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.useContents
import kotlinx.cinterop.usePinned
import platform.CoreGraphics.CGRectMake
import platform.CoreGraphics.CGSizeMake
import platform.Foundation.NSData
import platform.Foundation.create
import platform.UIKit.UIGraphicsBeginImageContextWithOptions
import platform.UIKit.UIGraphicsEndImageContext
import platform.UIKit.UIGraphicsGetImageFromCurrentImageContext
import platform.UIKit.UIImage
import platform.UIKit.UIImageJPEGRepresentation
import platform.posix.memcpy

@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
actual suspend fun compressImage(bytes: ByteArray, maxLongEdge: Int, quality: Int): ByteArray {
    val data = bytes.usePinned { NSData.create(bytes = it.addressOf(0), length = bytes.size.toULong()) }
    val image = UIImage.imageWithData(data) ?: throw IllegalArgumentException("Unsupported image data")

    // Drawing applies the EXIF orientation, so the output is always upright.
    val (w, h) = image.size.useContents { fitWithin(width.toInt(), height.toInt(), maxLongEdge) }
    UIGraphicsBeginImageContextWithOptions(CGSizeMake(w.toDouble(), h.toDouble()), true, 1.0)
    image.drawInRect(CGRectMake(0.0, 0.0, w.toDouble(), h.toDouble()))
    val resized = UIGraphicsGetImageFromCurrentImageContext()
    UIGraphicsEndImageContext()

    val jpeg = UIImageJPEGRepresentation(resized ?: image, quality.coerceIn(1, 100) / 100.0)
        ?: throw IllegalStateException("JPEG encoding failed")
    return ByteArray(jpeg.length.toInt()).also { out ->
        out.usePinned { memcpy(it.addressOf(0), jpeg.bytes, jpeg.length) }
    }
}
