package com.monster0506.divvy.ui.components

import androidx.compose.foundation.ScrollState
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.monster0506.divvy.ui.model.Person
import com.monster0506.divvy.ui.theme.DivvyColors

enum class Tab(val label: String) { Receipts("Receipts"), Balances("Balances"), Shopping("List"), Account("Account") }

/** [bottomBar] stays above the tabs while the content scrolls, for things like an undo bar. */
@Composable
fun AppScaffold(
    selected: Tab?,
    onSelect: (Tab) -> Unit = {},
    scrollState: ScrollState = rememberScrollState(),
    bottomBar: @Composable () -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(Modifier.fillMaxSize().background(DivvyColors.Surface).windowInsetsPadding(WindowInsets.safeDrawing)) {
        Column(Modifier.weight(1f).verticalScroll(scrollState), content = content)
        bottomBar()
        if (selected != null) {
            HorizontalDivider(color = DivvyColors.Line)
            Row(Modifier.fillMaxWidth()) {
                Tab.entries.forEach { tab ->
                    val on = tab == selected
                    Box(
                        Modifier.weight(1f).clickable { onSelect(tab) }.padding(top = 14.dp, bottom = 18.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            tab.label,
                            fontSize = 12.sp,
                            fontWeight = if (on) FontWeight.Bold else FontWeight.Medium,
                            color = if (on) DivvyColors.Ink else DivvyColors.Dim,
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun ScreenHeader(
    title: String,
    metaStart: String? = null,
    metaEnd: AnnotatedString? = null,
    onMetaEndClick: (() -> Unit)? = null,
    action: (@Composable () -> Unit)? = null,
) {
    Column(Modifier.fillMaxWidth().padding(start = 20.dp, top = 22.dp, end = 20.dp, bottom = 14.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(title, Modifier.weight(1f), fontSize = 22.sp, fontWeight = FontWeight.Bold, color = DivvyColors.Ink)
            action?.invoke()
        }
        if (metaStart != null || metaEnd != null) {
            Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(metaStart.orEmpty(), fontSize = 13.sp, color = DivvyColors.Dim)
                if (metaEnd != null) {
                    val tap = if (onMetaEndClick != null) Modifier.clickable(onClick = onMetaEndClick) else Modifier
                    Text(metaEnd, tap, fontSize = 13.sp, color = DivvyColors.Dim)
                }
            }
        }
    }
    HorizontalDivider(color = DivvyColors.Line)
}

@Composable
fun SectionHeader(start: String, end: String? = null, warn: Boolean = false, onClick: (() -> Unit)? = null) {
    val fg = if (warn) DivvyColors.Warn else DivvyColors.Dim
    val base = Modifier.fillMaxWidth().background(if (warn) DivvyColors.WarnBg else DivvyColors.Page)
    Row(
        (if (onClick != null) base.clickable { onClick() } else base).padding(horizontal = 20.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(start, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = fg)
        if (end != null) Text(end, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = fg)
    }
}

@Composable
fun RowDivider() = HorizontalDivider(color = DivvyColors.Line)

/**
 * The household's members by id, with the short names, icons, colors and photos they have saved. Avatars and owner
 * chips show a member's saved look from here, whichever screen built the [Person] (balances only know names).
 */
val LocalMembers = compositionLocalOf<Map<String, Person>> { emptyMap() }

/** [this] with its member's saved look, if they are one of [LocalMembers]. */
@Composable
private fun Person.withSavedLook(): Person {
    val member = LocalMembers.current[id] ?: return this
    return copy(initials = member.initials, color = member.color, emoji = member.emoji, photo = member.photo)
}

/** Shows the person's photo if set, otherwise their emoji, otherwise their initials on their avatar color. */
@Composable
fun Avatar(person: Person, size: Int = 32) {
    val shown = person.withSavedLook()
    Box(Modifier.size(size.dp).clip(CircleShape).background(shown.color.fill), contentAlignment = Alignment.Center) {
        val photo = shown.photo
        val emoji = shown.emoji
        when {
            photo != null -> Image(photo, shown.name, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            emoji != null -> Text(emoji, fontSize = (size * 0.5f).sp)
            else -> Text(
                shown.initials,
                fontSize = maxOf(11f, size / 3f).sp,
                fontWeight = FontWeight.SemiBold,
                color = DivvyColors.Ink,
            )
        }
    }
}

@Composable
fun OwnerPicker(
    people: List<Person>,
    owners: Set<String>,
    onToggle: (String) -> Unit,
    trailing: @Composable RowScope.() -> Unit = {},
) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        val everyone = people.isNotEmpty() && people.all { it.id in owners }
        val allFill by animateColorAsState(if (everyone) DivvyColors.Ink else DivvyColors.Surface, tween(150))
        val allEdge by animateColorAsState(if (everyone) DivvyColors.Ink else DivvyColors.Outline, tween(150))
        val allFg by animateColorAsState(if (everyone) Color.White else DivvyColors.Dim, tween(150))
        Box(
            Modifier.height(32.dp).clip(CircleShape)
                .background(allFill)
                .border(1.dp, allEdge, CircleShape)
                .clickable {
                    val targets = if (everyone) people else people.filter { it.id !in owners }
                    targets.forEach { onToggle(it.id) }
                }
                .padding(horizontal = 12.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text("All", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = allFg)
        }
        people.forEach { p ->
            val on = p.id in owners
            val fill by animateColorAsState(if (on) DivvyColors.Ink else DivvyColors.Surface, tween(150))
            val edge by animateColorAsState(if (on) DivvyColors.Ink else DivvyColors.Outline, tween(150))
            val fg by animateColorAsState(if (on) Color.White else DivvyColors.Dim, tween(150))
            Box(
                Modifier.size(32.dp).clip(CircleShape)
                    .background(fill)
                    .border(1.dp, edge, CircleShape)
                    .clickable { onToggle(p.id) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    p.withSavedLook().initials,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = fg,
                )
            }
        }
        trailing()
    }
}

@Composable
fun PrimaryButton(label: String, enabled: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val container by animateColorAsState(if (enabled) DivvyColors.Accent else DivvyColors.Line, tween(200))
    val content by animateColorAsState(if (enabled) Color.White else DivvyColors.Dim, tween(200))
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.fillMaxWidth().height(48.dp),
        shape = RoundedCornerShape(8.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = container,
            contentColor = content,
            disabledContainerColor = container,
            disabledContentColor = content,
        ),
    ) {
        AnimatedContent(label) { Text(it, fontSize = 15.sp, fontWeight = FontWeight.SemiBold) }
    }
}
