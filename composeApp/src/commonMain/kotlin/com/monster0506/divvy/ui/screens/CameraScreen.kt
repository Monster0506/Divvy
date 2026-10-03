package com.monster0506.divvy.ui.screens

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.monster0506.divvy.ui.theme.DivvyTheme

private val Viewfinder = Color(0xFF26292D)
private val Paper = Color(0xFFE9E7E0)
private val PaperInk = Color(0xFFB9B6AC)

@Composable
fun CameraScreen(onCapture: () -> Unit, onGallery: () -> Unit, onCancel: () -> Unit) {
    Column(Modifier.fillMaxSize().background(Color(0xFF111111))) {
        Box(Modifier.weight(1f).fillMaxWidth().background(Viewfinder), contentAlignment = Alignment.Center) {
            ReceiptPlaceholder()
        }
        Text(
            "Fit the whole receipt in the frame",
            Modifier.fillMaxWidth().padding(top = 18.dp),
            fontSize = 14.sp,
            color = Color(0xFFCFD2D6),
            textAlign = TextAlign.Center,
        )
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 36.dp, vertical = 26.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Gallery", Modifier.clickable(onClick = onGallery), fontSize = 14.sp, fontWeight = FontWeight.Medium, color = Color.White)
            val source = remember { MutableInteractionSource() }
            val pressed by source.collectIsPressedAsState()
            val inner by animateFloatAsState(if (pressed) 0.82f else 1f, tween(90))
            Box(
                Modifier.size(66.dp).border(4.dp, Color.White, CircleShape).clickable(source, null, onClick = onCapture),
                contentAlignment = Alignment.Center,
            ) {
                Box(Modifier.size(54.dp).scale(inner).background(Color.White, CircleShape))
            }
            Text("Cancel", Modifier.clickable(onClick = onCancel), fontSize = 14.sp, fontWeight = FontWeight.Medium, color = Color(0xFF8D939A))
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
    CameraScreen({}, {}, {})
}
