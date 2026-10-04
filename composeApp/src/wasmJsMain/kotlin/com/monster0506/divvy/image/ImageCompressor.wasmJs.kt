package com.monster0506.divvy.image

import kotlinx.coroutines.await
import org.khronos.webgl.Int8Array
import org.khronos.webgl.get
import org.khronos.webgl.set
import kotlin.js.Promise

// Decodes with createImageBitmap (applies EXIF orientation), resizes on a canvas, returns JPEG bytes.
private fun compressJs(data: Int8Array, maxLongEdge: Int, quality: Double): Promise<Int8Array> = js(
    """
    (async () => {
        const bitmap = await createImageBitmap(new Blob([data]), { imageOrientation: 'from-image' });
        const scale = Math.min(1, maxLongEdge / Math.max(bitmap.width, bitmap.height));
        const w = Math.max(1, Math.round(bitmap.width * scale));
        const h = Math.max(1, Math.round(bitmap.height * scale));
        const canvas = new OffscreenCanvas(w, h);
        const ctx = canvas.getContext('2d');
        ctx.fillStyle = '#ffffff';
        ctx.fillRect(0, 0, w, h);
        ctx.drawImage(bitmap, 0, 0, w, h);
        bitmap.close();
        const blob = await canvas.convertToBlob({ type: 'image/jpeg', quality });
        return new Int8Array(await blob.arrayBuffer());
    })()
    """
)

actual suspend fun compressImage(bytes: ByteArray, maxLongEdge: Int, quality: Int): ByteArray {
    val input = Int8Array(bytes.size)
    for (i in bytes.indices) input[i] = bytes[i]
    val output = compressJs(input, maxLongEdge, quality.coerceIn(1, 100) / 100.0).await<Int8Array>()
    return ByteArray(output.length) { output[it] }
}
