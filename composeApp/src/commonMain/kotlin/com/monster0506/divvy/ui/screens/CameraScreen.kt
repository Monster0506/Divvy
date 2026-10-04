package com.monster0506.divvy.ui.screens

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.monster0506.divvy.ui.theme.DivvyTheme

private val Viewfinder = Color(0xFF26292D)
private val Paper = Color(0xFFE9E7E0)
private val PaperInk = Color(0xFFB9B6AC)

enum class ReceiptPhotoStage { Empty, Preparing, Ready, Uploading, Uploaded }

@Composable
fun CameraScreen(
    photo: ImageBitmap?,
    stage: ReceiptPhotoStage,
    uploadProgress: Float?,
    uploadedReceiptId: String?,
    errorMessage: String?,
    cameraSupported: Boolean,
    onCapture: () -> Unit,
    onGallery: () -> Unit,
    onUpload: () -> Unit,
    onCancel: () -> Unit,
    onServerSettings: () -> Unit = {},
) {
    val canChoose = stage == ReceiptPhotoStage.Empty || stage == ReceiptPhotoStage.Ready
    Column(Modifier.fillMaxSize().background(Color(0xFF111111)).windowInsetsPadding(WindowInsets.safeDrawing)) {
        Box(Modifier.weight(1f).fillMaxWidth().background(Viewfinder), contentAlignment = Alignment.Center) {
            if (photo == null) ReceiptPlaceholder()
            else Image(
                bitmap = photo,
                contentDescription = "Receipt photo selected for upload",
                modifier = Modifier.fillMaxSize().padding(16.dp),
                contentScale = ContentScale.Fit,
            )
        }
        Text(
            when {
                errorMessage != null -> errorMessage
                stage == ReceiptPhotoStage.Uploaded -> "Receipt $uploadedReceiptId uploaded and processing."
                stage == ReceiptPhotoStage.Uploading -> "Uploading receipt${uploadProgress?.let { " — ${(it * 100).toInt()}%" } ?: "…"}"
                stage == ReceiptPhotoStage.Preparing -> "Preparing photo…"
                stage == ReceiptPhotoStage.Ready -> "Photo ready. Upload it or take another."
                !cameraSupported -> "Camera capture is available on Android and iOS. You can choose a photo from Gallery."
                else -> "Tap the shutter to open the camera"
            },
            Modifier.fillMaxWidth().padding(top = 18.dp),
            fontSize = 14.sp,
            color = if (errorMessage != null) Color(0xFFFFB4AB) else Color(0xFFCFD2D6),
            textAlign = TextAlign.Center,
        )
        if (stage == ReceiptPhotoStage.Uploading) {
            if (uploadProgress == null) LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 36.dp, vertical = 12.dp))
            else LinearProgressIndicator(
                progress = { uploadProgress },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 36.dp, vertical = 12.dp),
            )
        }
        if (stage == ReceiptPhotoStage.Ready) {
            Button(onClick = onUpload, modifier = Modifier.fillMaxWidth().padding(horizontal = 36.dp, vertical = 12.dp)) {
                Text(if (errorMessage == null) "Upload photo" else "Retry upload")
            }
        }
        if (errorMessage != null && stage == ReceiptPhotoStage.Ready) Text(
            "Server settings",
            Modifier.fillMaxWidth().clickable(onClick = onServerSettings).padding(vertical = 4.dp),
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            color = Color.White,
            textAlign = TextAlign.Center,
        )
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 36.dp, vertical = 26.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Gallery", Modifier.clickable(enabled = canChoose, onClick = onGallery), fontSize = 14.sp, fontWeight = FontWeight.Medium, color = if (canChoose) Color.White else Color.Gray)
            val source = remember { MutableInteractionSource() }
            val pressed by source.collectIsPressedAsState()
            val inner by animateFloatAsState(if (pressed) 0.82f else 1f, tween(90))
            Box(
                Modifier.size(66.dp)
                    .border(4.dp, if (cameraSupported && canChoose) Color.White else Color.Gray, CircleShape)
                    .semantics { contentDescription = "Open camera to take a receipt photo" }
                    .clickable(interactionSource = source, indication = null, enabled = cameraSupported && canChoose, onClick = onCapture),
                contentAlignment = Alignment.Center,
            ) {
                Box(Modifier.size(54.dp).scale(inner).background(if (cameraSupported && canChoose) Color.White else Color.Gray, CircleShape))
            }
            Text(if (stage == ReceiptPhotoStage.Uploaded) "Done" else "Cancel", Modifier.clickable(enabled = stage != ReceiptPhotoStage.Uploading && stage != ReceiptPhotoStage.Preparing, onClick = onCancel), fontSize = 14.sp, fontWeight = FontWeight.Medium, color = Color(0xFF8D939A))
        }
    }
}

@Composable
private fun ReceiptPlaceholder() {
    val widths = listOf(0.6f, 1f, 0.8f, 1f, 0.7f, 1f, 0.85f, 0.5f)
    Column(
        Modifier.fillMaxWidth(0.62f).fillMaxHeight(0.62f).background(Paper, RoundedCornerShape(2.dp)).padding(horizontal = 12.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        widths.forEach { Box(Modifier.fillMaxWidth(it).height(4.dp).background(PaperInk)) }
    }
}

@Preview(widthDp = 390, heightDp = 800, showBackground = true)
@Composable
private fun CameraPreview() = DivvyTheme {
    CameraScreen(null, ReceiptPhotoStage.Empty, null, null, null, true, {}, {}, {}, {})
}
