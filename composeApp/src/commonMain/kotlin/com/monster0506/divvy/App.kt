package com.monster0506.divvy

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Alignment
import com.monster0506.divvy.data.api.CreateHouseholdResult
import com.monster0506.divvy.data.api.CreatePersonResult
import com.monster0506.divvy.data.api.HouseholdApi
import com.monster0506.divvy.data.api.PeopleApi
import com.monster0506.divvy.data.api.createHttpClient
import com.monster0506.divvy.launch.HouseholdStore
import com.monster0506.divvy.launch.LaunchDestination
import com.monster0506.divvy.launch.LaunchRouter
import com.monster0506.divvy.launch.LocalHouseholdLookup
import com.russhwolf.settings.Settings
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import org.jetbrains.compose.resources.decodeToImageBitmap
import com.monster0506.divvy.data.api.ApiException
import com.monster0506.divvy.data.api.ApiFailure
import com.monster0506.divvy.data.api.ApiConfig
import com.monster0506.divvy.data.api.ApiConfigStore
import com.monster0506.divvy.data.api.DivvyApi
import com.monster0506.divvy.data.api.createHttpClient
import com.monster0506.divvy.image.prepareReceiptImage
import com.monster0506.divvy.launch.LOCAL_HOUSEHOLD_ID
import com.monster0506.divvy.platform.rememberCameraCapture
import com.monster0506.divvy.platform.rememberGalleryPicker
import com.monster0506.divvy.ui.components.Tab
import com.monster0506.divvy.ui.model.ReceiptStatus
import com.monster0506.divvy.ui.screens.AccountScreen
import com.monster0506.divvy.ui.screens.AddMembersScreen
import com.monster0506.divvy.ui.screens.BalancesScreen
import com.monster0506.divvy.ui.screens.CameraScreen
import com.monster0506.divvy.ui.screens.ReceiptPhotoStage
import com.monster0506.divvy.ui.screens.CreateHouseholdScreen
import com.monster0506.divvy.ui.screens.EditPersonScreen
import com.monster0506.divvy.ui.screens.HouseholdSetupScreen
import com.monster0506.divvy.ui.screens.ReceiptDetailScreen
import com.monster0506.divvy.ui.screens.ReceiptHistoryScreen
import com.monster0506.divvy.ui.screens.ReviewReceiptScreen
import com.monster0506.divvy.ui.screens.ShoppingListScreen
import com.monster0506.divvy.ui.state.AddMembersState
import com.monster0506.divvy.ui.state.CreateHouseholdState
import com.monster0506.divvy.ui.state.DivvyState
import com.monster0506.divvy.ui.theme.DivvyTheme
import androidx.compose.ui.tooling.preview.Preview
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

enum class Route { Launch, CreateHousehold, AddMembers, Setup, Camera, Review, History, Detail, Balances, Shopping, Account, EditPerson }

private data class SelectedReceiptPhoto(val jpeg: ByteArray, val preview: ImageBitmap)

private fun Throwable.uploadMessage(): String = when (this) {
    is ApiException -> when (val reason = failure) {
        ApiFailure.Offline -> "Could not reach the server. Check the URL in Account > Server and try again."
        ApiFailure.Unauthorised -> "The server rejected the API key. Check Account > Server."
        is ApiFailure.Validation -> if (reason.error.code == "http_404") {
            "No receipt upload endpoint at this server address. Check the URL or update the server."
        } else reason.error.message
        is ApiFailure.ServerError -> if (reason.status == 503) {
            "Server unavailable (503). Check that the backend is running and try again."
        } else "The server could not save this receipt. Please try again."
    }
    else -> "Could not prepare this photo. Please choose a different image."
}

@Composable
fun App(apiConfig: ApiConfig = ApiConfig()) {
    val settings = remember { Settings() }
    val router = remember(settings) { LaunchRouter(HouseholdStore(settings), LocalHouseholdLookup()) }
    val configStore = remember(settings) { ApiConfigStore(settings) }
    var activeConfig by remember(configStore) { mutableStateOf(configStore.load(apiConfig)) }
    val client = remember(activeConfig) { createHttpClient(activeConfig) }
    DisposableEffect(client) { onDispose { client.close() } }
    val householdApi = remember(client) { HouseholdApi(client) }
    val peopleApi = remember(client) { PeopleApi(client) }
    DivvyTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            DivvyApp(
                start = Route.Launch,
                router = router,
                apiConfig = activeConfig,
                onApiConfigChange = {
                    configStore.save(it)
                    activeConfig = it
                },
                createHousehold = householdApi::create,
                createPerson = peopleApi::create,
            )
        }
    }
}

@Composable
fun DivvyApp(
    start: Route = Route.Setup,
    state: DivvyState = remember { DivvyState() },
    router: LaunchRouter? = null,
    apiConfig: ApiConfig = ApiConfig(),
    onApiConfigChange: (ApiConfig) -> Unit = {},
    createHousehold: suspend (String) -> CreateHouseholdResult = {
        CreateHouseholdResult.Failure("Not connected")
    },
    createPerson: suspend (householdId: String, name: String) -> CreatePersonResult = { _, _ ->
        CreatePersonResult.Failure("Not connected")
    },
) {
    var route by remember { mutableStateOf(start) }
    var householdId by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(router) {
        if (start == Route.Launch && router != null) {
            route = when (router.decide()) {
                LaunchDestination.Home -> Route.History
                LaunchDestination.Onboarding -> Route.CreateHousehold
            }
        }
    }
    var editingId by remember { mutableStateOf(state.meId) }
    var cameraError by remember { mutableStateOf<String?>(null) }
    var selectedPhoto by remember { mutableStateOf<SelectedReceiptPhoto?>(null) }
    var photoStage by remember { mutableStateOf(ReceiptPhotoStage.Empty) }
    var uploadProgress by remember { mutableStateOf<Float?>(null) }
    var uploadedReceiptId by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val client = remember(apiConfig) { createHttpClient(apiConfig) }
    DisposableEffect(client) { onDispose { client.close() } }
    val api = remember(client) { DivvyApi(client) }
    val onTab = { tab: Tab ->
        route = when (tab) {
            Tab.Receipts -> Route.History
            Tab.Balances -> Route.Balances
            Tab.Shopping -> Route.Shopping
            Tab.Account -> Route.Account
        }
    }
    fun selectPhoto(bytes: ByteArray) {
        scope.launch {
            photoStage = ReceiptPhotoStage.Preparing
            cameraError = null
            uploadedReceiptId = null
            try {
                val jpeg = prepareReceiptImage(bytes)
                selectedPhoto = SelectedReceiptPhoto(jpeg, jpeg.decodeToImageBitmap())
                photoStage = ReceiptPhotoStage.Ready
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                cameraError = e.uploadMessage()
                photoStage = if (selectedPhoto == null) ReceiptPhotoStage.Empty else ReceiptPhotoStage.Ready
            }
        }
    }
    fun uploadPhoto() {
        val photo = selectedPhoto ?: return
        if (photoStage != ReceiptPhotoStage.Ready) return
        scope.launch {
            photoStage = ReceiptPhotoStage.Uploading
            uploadProgress = null
            cameraError = null
            try {
                val savedId = router?.currentHouseholdId()
                suspend fun createServerHousehold(): String =
                    api.createHousehold(state.householdName).id.also { router?.onboardingCompleted(it) }
                val householdId = if (savedId.isNullOrBlank() || savedId == LOCAL_HOUSEHOLD_ID) {
                    createServerHousehold()
                } else savedId
                val progress: (Long, Long) -> Unit = { sent, total ->
                    if (total > 0) uploadProgress = (sent.toFloat() / total).coerceIn(0f, 1f)
                }
                val uploaded = try {
                    api.uploadReceipt(householdId, photo.jpeg, progress)
                } catch (e: ApiException) {
                    // A local household ID may outlive a reset development server.
                    if (savedId == householdId &&
                        e.failure is ApiFailure.Validation &&
                        e.failure.error.code == "not_found"
                    ) {
                        api.uploadReceipt(createServerHousehold(), photo.jpeg, progress)
                    } else throw e
                }
                uploadedReceiptId = uploaded.id
                photoStage = ReceiptPhotoStage.Uploaded
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                cameraError = e.uploadMessage()
                photoStage = ReceiptPhotoStage.Ready
            }
        }
    }
    val cameraCapture = rememberCameraCapture(
        onResult = ::selectPhoto,
        onError = { cameraError = "Camera unavailable. Check camera access in Settings, or choose a photo from Gallery." },
    )
    val galleryPicker = rememberGalleryPicker { bytes ->
        if (bytes != null) selectPhoto(bytes)
    }
    AnimatedContent(
        targetState = route,
        transitionSpec = { fadeIn(tween(200)) togetherWith fadeOut(tween(120)) },
    ) { current ->
        when (current) {
            Route.Launch -> Box(Modifier.fillMaxSize()) {
                CircularProgressIndicator(Modifier.align(Alignment.Center))
            }

            Route.CreateHousehold -> {
                val scope = rememberCoroutineScope()
                val form = remember { CreateHouseholdState(createHousehold) }
                CreateHouseholdScreen(
                    state = form,
                    onContinue = {
                        scope.launch {
                            val id = form.submit() ?: return@launch
                            householdId = id
                            state.householdName = form.name.trim()
                            route = Route.AddMembers
                        }
                    },
                )
            }

            Route.AddMembers -> {
                val scope = rememberCoroutineScope()
                val form = remember { AddMembersState { name -> createPerson(householdId.orEmpty(), name) } }
                AddMembersScreen(
                    state = form,
                    onFinish = {
                        scope.launch {
                            if (!form.finish()) return@launch
                            router?.onboardingCompleted(householdId.orEmpty())
                            route = Route.History
                        }
                    },
                )
            }

            Route.Setup -> HouseholdSetupScreen(
                onStart = { route = Route.History },
                householdName = state.householdName,
                members = state.people,
                newMember = state.newMember,
                onNameChange = { state.householdName = it },
                onNewMemberChange = { state.newMember = it },
                onAddMember = state::addMember,
                onRemoveMember = state::removePerson,
                canRemove = state::canRemove,

            )

            Route.Camera -> CameraScreen(
                photo = selectedPhoto?.preview,
                stage = photoStage,
                uploadProgress = uploadProgress,
                uploadedReceiptId = uploadedReceiptId,
                errorMessage = cameraError,
                cameraSupported = cameraCapture.isSupported,
                onCapture = {
                    cameraError = null
                    cameraCapture.launch()
                },
                onGallery = { galleryPicker.launch() },
                onUpload = ::uploadPhoto,
                onCancel = {
                    cameraError = null
                    selectedPhoto = null
                    photoStage = ReceiptPhotoStage.Empty
                    uploadProgress = null
                    uploadedReceiptId = null
                    route = Route.History
                },
                onServerSettings = { route = Route.Account },
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
                onEditLine = state::editLine,
                onAddLine = state::addLine,
                onDeleteLine = state::deleteLine,
                onMergeLines = state::mergeLines,
                canUndo = state.canUndoDelete,
                onUndo = state::undoDelete,
            )

            Route.History -> ReceiptHistoryScreen(
                entries = state.history,
                onOpen = {
                    route =
                        if (it == 0 && state.history[0].status == ReceiptStatus.NeedsReview) Route.Review else Route.Detail
                },
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
                apiConfig = apiConfig,
                onApiConfigChange = onApiConfigChange,
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
