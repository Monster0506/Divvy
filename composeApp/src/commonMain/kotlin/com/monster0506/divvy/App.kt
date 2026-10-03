package com.monster0506.divvy

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.monster0506.divvy.ui.components.Tab
import com.monster0506.divvy.ui.model.ReceiptStatus
import com.monster0506.divvy.ui.screens.AccountScreen
import com.monster0506.divvy.ui.screens.BalancesScreen
import com.monster0506.divvy.ui.screens.CameraScreen
import com.monster0506.divvy.ui.screens.EditPersonScreen
import com.monster0506.divvy.ui.screens.HouseholdSetupScreen
import com.monster0506.divvy.ui.screens.ReceiptDetailScreen
import com.monster0506.divvy.ui.screens.ReceiptHistoryScreen
import com.monster0506.divvy.ui.screens.ReviewReceiptScreen
import com.monster0506.divvy.ui.screens.ShoppingListScreen
import com.monster0506.divvy.ui.state.DivvyState
import com.monster0506.divvy.ui.theme.DivvyTheme
import androidx.compose.ui.tooling.preview.Preview

enum class Route { Setup, Camera, Review, History, Detail, Balances, Shopping, Account, EditPerson }

@Composable
fun App() {
    DivvyTheme {
        Surface(modifier = Modifier.fillMaxSize()) { DivvyApp() }
    }
}

@Composable
fun DivvyApp(start: Route = Route.Setup, state: DivvyState = remember { DivvyState() }) {
    var route by remember { mutableStateOf(start) }
    var editingId by remember { mutableStateOf(state.meId) }
    val onTab = { tab: Tab ->
        route = when (tab) {
            Tab.Receipts -> Route.History
            Tab.Balances -> Route.Balances
            Tab.Shopping -> Route.Shopping
            Tab.Account -> Route.Account
        }
    }
    AnimatedContent(
        targetState = route,
        transitionSpec = { fadeIn(tween(200)) togetherWith fadeOut(tween(120)) },
    ) { current ->
        when (current) {
            Route.Setup -> HouseholdSetupScreen(
                householdName = state.householdName,
                members = state.people,
                newMember = state.newMember,
                onNameChange = { state.householdName = it },
                onNewMemberChange = { state.newMember = it },
                onAddMember = state::addMember,
                onRemoveMember = state::removePerson,
                canRemove = state::canRemove,
                onStart = { route = Route.History },
            )
            Route.Camera -> CameraScreen(
                onCapture = { route = Route.Review },
                onGallery = { route = Route.Review },
                onCancel = { route = Route.History },
            )
            Route.Review -> ReviewReceiptScreen(
                draft = state.draft,
                autoExpanded = state.autoExpanded,
                onToggleOwner = state::toggleOwner,
                onConfirmLine = state::confirmLine,
                onToggleAuto = state::toggleAuto,
                onConfirmSplit = {
                    state.confirmSplit()
                    route = Route.Balances
                },
                onTab = onTab,
            )
            Route.History -> ReceiptHistoryScreen(
                entries = state.history,
                onOpen = { route = if (it == 0 && state.history[0].status == ReceiptStatus.NeedsReview) Route.Review else Route.Detail },
                onScan = { route = Route.Camera },
                onTab = onTab,
            )
            Route.Detail -> ReceiptDetailScreen(
                receipt = state.resolvedDraft,
                shares = state.shares,
                onViewPhoto = {},
                onReopen = {},
                onTab = onTab,
            )
            Route.Balances -> BalancesScreen(state.balances, state.transfers, state::togglePaid, onTab)
            Route.Shopping -> ShoppingListScreen(
                items = state.shopping,
                suggestions = state.suggestions,
                onToggle = state::toggleShopping,
                onAddSuggestion = state::addSuggestion,
                onTab = onTab,
            )
            Route.Account -> AccountScreen(
                me = state.me,
                householdName = state.householdName,
                people = state.people,
                settings = state.settings,
                onHouseholdNameChange = { state.householdName = it },
                onEditPerson = {
                    editingId = it
                    route = Route.EditPerson
                },
                onAddMember = {
                    editingId = state.addPerson("New member")
                    route = Route.EditPerson
                },
                onSettingsChange = { state.settings = it },
                onTab = onTab,
            )
            Route.EditPerson -> state.person(editingId)?.let { person ->
                EditPersonScreen(
                    person = person,
                    isMe = person.id == state.meId,
                    canRemove = state.canRemove(person.id),
                    onChange = state::updatePerson,
                    onRemove = {
                        route = Route.Account
                        state.removePerson(person.id)
                    },
                    onDone = { route = Route.Account },
                    onTab = onTab,
                )
            }
        }
    }
}

@Preview(widthDp = 390, heightDp = 800, showBackground = true)
@Composable
private fun AppPreview() = DivvyTheme {
    Surface { DivvyApp(start = Route.Review) }
}
