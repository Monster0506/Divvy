package com.monster0506.divvy.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.monster0506.divvy.image.compressImage
import com.monster0506.divvy.ui.components.AppScaffold
import com.monster0506.divvy.ui.components.Avatar
import com.monster0506.divvy.ui.components.RowDivider
import com.monster0506.divvy.ui.components.ScreenHeader
import com.monster0506.divvy.ui.components.SectionHeader
import com.monster0506.divvy.ui.components.Tab
import com.monster0506.divvy.ui.model.AvatarColor
import com.monster0506.divvy.ui.model.Person
import com.monster0506.divvy.ui.model.initialsFor
import com.monster0506.divvy.ui.preview.SampleData
import com.monster0506.divvy.ui.theme.DivvyColors
import com.monster0506.divvy.ui.theme.DivvyTheme
import io.github.vinceglb.filekit.dialogs.FileKitType
import io.github.vinceglb.filekit.dialogs.compose.rememberFilePickerLauncher
import io.github.vinceglb.filekit.readBytes
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.decodeToImageBitmap

private val emojis = listOf("🍕", "🥑", "🌮", "🍜", "☕", "🍩", "🥦", "🐶", "🐱", "🌱", "⚽", "🎮", "🎸", "📚", "🚲", "⭐")

/** A photo picked for an avatar: the small JPEG that is uploaded, and the image shown meanwhile. */
class PickedPhoto(val jpeg: ByteArray, val preview: ImageBitmap)

/** Edits apply immediately and are saved to the server; there is nothing to save by hand. */
@Composable
fun EditPersonScreen(
    person: Person,
    isMe: Boolean,
    canRemove: Boolean,
    onChange: (Person) -> Unit,
    onRemove: () -> Unit,
    onDone: () -> Unit,
    /** A new photo, or null to remove theirs. */
    onPhoto: (PickedPhoto?) -> Unit = {},
    onTab: (Tab) -> Unit = {},
    nameMessage: String? = null,
    /** Why the last photo, short name, icon or color change wasn't saved. */
    lookMessage: String? = null,
) {
    val scope = rememberCoroutineScope()
    var confirmingRemove by remember { mutableStateOf(false) }
    val pickPhoto = rememberFilePickerLauncher(type = FileKitType.Image) { file ->
        if (file != null) scope.launch {
            val photo = runCatching {
                val jpeg = compressImage(file.readBytes(), maxLongEdge = AVATAR_LONG_EDGE, quality = AVATAR_JPEG_QUALITY)
                PickedPhoto(jpeg, jpeg.decodeToImageBitmap().toAvatarPhoto())
            }.getOrNull()
            if (photo != null) onPhoto(photo)
        }
    }

    AppScaffold(selected = Tab.Account, onSelect = onTab) {
        ScreenHeader(if (isMe) "Your profile" else person.name.ifBlank { "Edit member" }, action = {
            TextButton(onClick = onDone) {
                Text("Done", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = DivvyColors.Accent)
            }
        })

        Column(
            Modifier.fillMaxWidth().padding(vertical = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Avatar(person, size = 96)
            Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { pickPhoto.launch() }, shape = RoundedCornerShape(8.dp)) {
                    Text(
                        if (person.photo == null) "Choose photo" else "Change photo",
                        fontWeight = FontWeight.SemiBold,
                        color = DivvyColors.Ink,
                    )
                }
                if (person.photo != null) {
                    TextButton(onClick = { onPhoto(null) }) {
                        Text("Remove photo", color = DivvyColors.Dim)
                    }
                }
            }
            if (lookMessage != null) {
                Text(
                    lookMessage,
                    fontSize = 13.sp,
                    color = DivvyColors.Warn,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(start = 20.dp, top = 8.dp, end = 20.dp),
                )
            }
        }

        SectionHeader("Details")
        OutlinedTextField(
            value = person.name,
            onValueChange = { name ->
                // Keep the short name in step with the name until it's been customised.
                val auto = person.initials == initialsFor(person.name)
                onChange(person.copy(name = name, initials = if (auto) initialsFor(name) else person.initials))
            },
            label = { Text("Name") },
            singleLine = true,
            isError = nameMessage != null,
            supportingText = nameMessage?.let { message -> { Text(message) } },
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier.fillMaxWidth().padding(start = 20.dp, top = 14.dp, end = 20.dp),
        )
        OutlinedTextField(
            value = person.initials,
            onValueChange = { onChange(person.copy(initials = it.trim().take(2))) },
            label = { Text("Short name") },
            supportingText = { Text("Shown in avatars and when assigning items") },
            singleLine = true,
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier.fillMaxWidth().padding(start = 20.dp, top = 8.dp, end = 20.dp, bottom = 8.dp),
        )

        SectionHeader("Icon", if (person.photo != null) "Shown when there's no photo" else null)
        FlowRow(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Choice(selected = person.emoji == null, onClick = { onChange(person.copy(emoji = null)) }) {
                Text(person.initials, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = DivvyColors.Ink)
            }
            emojis.forEach { e ->
                Choice(selected = person.emoji == e, onClick = { onChange(person.copy(emoji = e)) }) {
                    Text(e, fontSize = 20.sp)
                }
            }
        }

        SectionHeader("Color")
        FlowRow(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            AvatarColor.entries.forEach { c ->
                Choice(selected = person.color == c, onClick = { onChange(person.copy(color = c)) }, fill = c) {}
            }
        }

        if (canRemove) {
            RowDivider()
            TextButton(
                onClick = { confirmingRemove = true },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
            ) {
                Text("Remove from household", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = DivvyColors.Warn)
            }
        }
        Spacer(Modifier.height(16.dp))
    }

    if (confirmingRemove) {
        AlertDialog(
            onDismissRequest = { confirmingRemove = false },
            title = { Text("Remove ${person.name.ifBlank { "this member" }}?") },
            text = {
                Text("They are archived, not deleted. Their past receipts and balances stay, and they won't be added to new splits.")
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmingRemove = false
                    onRemove()
                }) { Text("Remove") }
            },
            dismissButton = {
                TextButton(onClick = { confirmingRemove = false }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun Choice(
    selected: Boolean,
    onClick: () -> Unit,
    fill: AvatarColor = AvatarColor.Gray,
    content: @Composable () -> Unit,
) {
    Box(
        Modifier.size(44.dp).clip(CircleShape).background(fill.fill)
            .border(if (selected) 2.dp else 1.dp, if (selected) DivvyColors.Ink else DivvyColors.Line, CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { content() }
}

/** Uploaded avatar photos are small: the long edge is at most this many pixels. */
private const val AVATAR_LONG_EDGE = 512
private const val AVATAR_JPEG_QUALITY = 85

/** Center-crops to a square and scales down, so a full-size camera photo isn't kept in memory per avatar. */
private fun ImageBitmap.toAvatarPhoto(sizePx: Int = 256): ImageBitmap {
    val side = minOf(width, height)
    val out = ImageBitmap(sizePx, sizePx)
    Canvas(out).drawImageRect(
        this,
        srcOffset = IntOffset((width - side) / 2, (height - side) / 2),
        srcSize = IntSize(side, side),
        dstSize = IntSize(sizePx, sizePx),
        paint = Paint().apply { filterQuality = FilterQuality.High },
    )
    return out
}

@Preview(widthDp = 390, heightDp = 1000, showBackground = true)
@Composable
private fun EditPersonPreview() = DivvyTheme {
    EditPersonScreen(SampleData.tj, isMe = false, canRemove = true, {}, {}, {})
}

