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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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

/** Edits apply immediately; there is nothing to save. */
@Composable
fun EditPersonScreen(
    person: Person,
    isMe: Boolean,
    canRemove: Boolean,
    onChange: (Person) -> Unit,
    onRemove: () -> Unit,
    onDone: () -> Unit,
    onTab: (Tab) -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    val pickPhoto = rememberFilePickerLauncher(type = FileKitType.Image) { file ->
        if (file != null) scope.launch {
            val photo = runCatching { file.readBytes().decodeToImageBitmap().toAvatarPhoto() }.getOrNull()
            if (photo != null) onChange(person.copy(photo = photo))
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
                    TextButton(onClick = { onChange(person.copy(photo = null)) }) {
                        Text("Remove photo", color = DivvyColors.Dim)
                    }
                }
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
                onClick = onRemove,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
            ) {
                Text("Remove from household", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = DivvyColors.Warn)
            }
        }
        Spacer(Modifier.height(16.dp))
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
    EditPersonScreen(SampleData.tj.copy(emoji = "🍕"), isMe = false, canRemove = true, {}, {}, {})
}
