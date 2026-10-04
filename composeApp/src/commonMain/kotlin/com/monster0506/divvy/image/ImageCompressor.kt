package com.monster0506.divvy.image

const val DEFAULT_MAX_LONG_EDGE = 2000
const val DEFAULT_JPEG_QUALITY = 80

/** Target size that fits the long edge within [maxLongEdge], keeping aspect ratio and never upscaling. */
fun fitWithin(width: Int, height: Int, maxLongEdge: Int): Pair<Int, Int> {
    require(width > 0 && height > 0) { "Image dimensions must be positive" }
    require(maxLongEdge > 0) { "maxLongEdge must be positive" }
    val longEdge = maxOf(width, height)
    if (longEdge <= maxLongEdge) return width to height
    val scale = maxLongEdge.toDouble() / longEdge
    val w = (width * scale).toInt().coerceAtLeast(1)
    val h = (height * scale).toInt().coerceAtLeast(1)
    return w to h
}

/** Decodes [bytes], downscales so the long edge is at most [maxLongEdge], and re-encodes as JPEG. */
expect suspend fun compressImage(
    bytes: ByteArray,
    maxLongEdge: Int = DEFAULT_MAX_LONG_EDGE,
    quality: Int = DEFAULT_JPEG_QUALITY,
): ByteArray

/** Prepares a legible JPEG for upload, reducing size further if a dense photo is still large. */
suspend fun prepareReceiptImage(bytes: ByteArray): ByteArray {
    require(bytes.isNotEmpty()) { "Image is empty" }
    val first = compressImage(bytes)
    if (first.size <= 2_000_000) return first
    val second = compressImage(bytes, maxLongEdge = 1800, quality = 70)
    if (second.size <= 2_000_000) return second
    return compressImage(bytes, maxLongEdge = 1600, quality = 65)
}
