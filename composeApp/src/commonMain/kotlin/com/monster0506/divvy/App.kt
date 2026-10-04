package com.monster0506.divvy

import androidx.compose.foundation.rememberScrollState
import com.monster0506.divvy.domain.parseDateInput
import com.monster0506.divvy.ui.screens.HistoryFilters
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Alignment
import com.monster0506.divvy.data.api.SettingsResult
import com.monster0506.divvy.data.api.CreateHouseholdResult
import com.monster0506.divvy.data.api.CreatePersonResult
import com.monster0506.divvy.data.api.HouseholdApi
import com.monster0506.divvy.data.api.HouseholdMember
import com.monster0506.divvy.data.api.HouseholdSettingsApi
import com.monster0506.divvy.data.api.PeopleApi
import com.monster0506.divvy.data.api.createHttpClient
import com.monster0506.divvy.launch.HouseholdStore
import com.monster0506.divvy.launch.HouseholdMemberStore
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
import com.monster0506.divvy.image.prepareReceiptImage
import com.monster0506.divvy.launch.LOCAL_HOUSEHOLD_ID
import com.monster0506.divvy.platform.rememberCameraCapture
import com.monster0506.divvy.platform.rememberGalleryPicker
import com.monster0506.divvy.ui.components.Tab
import com.monster0506.divvy.ui.model.ReceiptStatus
import com.monster0506.divvy.ui.screens.AccountScreen
import com.monster0506.divvy.ui.screens.AddMembersScreen
import com.monster0506.divvy.ui.screens.JoinHouseholdScreen
import com.monster0506.divvy.ui.screens.LiveBalancesScreen
import com.monster0506.divvy.ui.screens.CameraScreen
import com.monster0506.divvy.ui.screens.ReceiptPhotoStage
import com.monster0506.divvy.ui.screens.CreateHouseholdScreen
import com.monster0506.divvy.ui.screens.EditPersonScreen
import com.monster0506.divvy.ui.screens.HouseholdSettingsScreen
import com.monster0506.divvy.ui.screens.HouseholdSetupScreen
import com.monster0506.divvy.ui.screens.ReceiptDetailScreen
import com.monster0506.divvy.ui.screens.ReceiptHistoryScreen
import com.monster0506.divvy.ui.screens.ReviewReceiptScreen
import com.monster0506.divvy.ui.screens.ReviewStatusScreen
import com.monster0506.divvy.history.DetailPhase
import com.monster0506.divvy.history.ReceiptDetailController
import com.monster0506.divvy.history.ReceiptHistoryController
import com.monster0506.divvy.history.RepositoryBrowseApi
import com.monster0506.divvy.settings.AutoAssignController
import com.monster0506.divvy.settings.HouseholdNameController
import com.monster0506.divvy.settings.MemberSyncController
import com.monster0506.divvy.settings.RepositorySettingsApi
import com.monster0506.divvy.data.repository.SettingsRepository
import com.monster0506.divvy.data.dto.ReceiptStatusDto
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.window.Dialog
import com.monster0506.divvy.review.ReceiptReviewController
import com.monster0506.divvy.review.ReceiptDetailsEdit
import com.monster0506.divvy.review.PayerStore
import com.monster0506.divvy.review.RepositoryReviewApi
import com.monster0506.divvy.review.ReviewPhase
import com.monster0506.divvy.balances.BalancesController
import com.monster0506.divvy.balances.VENMO_NOTE
import com.monster0506.divvy.platform.payWithVenmo
import com.monster0506.divvy.platform.requestWithVenmo
import com.monster0506.divvy.platform.VenmoUserIds
import com.monster0506.divvy.platform.rememberUrlOpener
import com.monster0506.divvy.data.repository.BalanceRepository
import com.monster0506.divvy.data.repository.HouseholdRepository
import com.monster0506.divvy.data.repository.ReceiptRepository
import androidx.compose.runtime.collectAsState
import com.monster0506.divvy.ui.screens.ShoppingItemScreen
import com.monster0506.divvy.ui.screens.ShoppingListScreen
import com.monster0506.divvy.data.repository.ShoppingListRepository
import com.monster0506.divvy.shopping.LocalShoppingListApi
import com.monster0506.divvy.shopping.RepositoryShoppingListApi
import com.monster0506.divvy.shopping.ShoppingListController
import com.monster0506.divvy.ui.preview.SampleData
import com.monster0506.divvy.ui.state.AddMembersState
import com.monster0506.divvy.ui.state.CreateHouseholdState
import com.monster0506.divvy.ui.state.JoinHouseholdState
import com.monster0506.divvy.ui.state.DivvyState
import com.monster0506.divvy.ui.state.HouseholdSettingsState
import com.monster0506.divvy.ui.theme.DivvyTheme
import androidx.compose.ui.tooling.preview.Preview
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

enum class Route { Launch, CreateHousehold, AddMembers, JoinHousehold, Setup, Camera, Review, History, Detail, Balances, Shopping, ShoppingItem, Account, EditPerson, HouseholdSettings }

private data class SelectedReceiptPhoto(val jpeg: ByteArray, val preview: ImageBitmap)

private val serverId = Regex("^[0-9a-fA-F]{8}-([0-9a-fA-F]{4}-){3}[0-9a-fA-F]{12}$")

/** Things to offer while typing on the list before the household is on a server. */
private val sampleGroceries =
    listOf("Eggs", "Large eggs", "Eggplant", "Milk", "Oat milk", "Sandwich bread", "Bananas", "Paper towels")

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
    val settingsApi = remember(client) { HouseholdSettingsApi(client) }
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
                loadHouseholdMembers = householdApi::members,
                settingsApi = settingsApi,
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
    loadHouseholdMembers: suspend (String) -> List<HouseholdMember> = { emptyList() },
    settingsApi: HouseholdSettingsApi? = null,
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
    val payerStore = remember { PayerStore(Settings()) }
    val memberStore = remember { HouseholdMemberStore(Settings()) }
    var reviewHouseholdId by remember { mutableStateOf("") }
    val review = remember(api) {
        ReceiptReviewController(
            RepositoryReviewApi(ReceiptRepository(api), HouseholdRepository(api)),
            scope,
            onPayerChosen = { payerStore.save(reviewHouseholdId, it) },
        )
    }
    DisposableEffect(review) { onDispose { review.close() } }
    val reviewState by review.state.collectAsState()
    val browse = remember(api) { RepositoryBrowseApi(ReceiptRepository(api), HouseholdRepository(api)) }
    val history = remember(browse) { ReceiptHistoryController(browse, scope) }
    val detail = remember(browse) { ReceiptDetailController(browse, scope) }
    val autoAssign = remember(api) { AutoAssignController(RepositorySettingsApi(SettingsRepository(api)), scope) }
    val nameSync = remember(api) {
        HouseholdNameController(
            scope = scope,
            fetch = { id -> api.getHousehold(id).name },
            rename = { id, name -> api.renameHousehold(id, name).name },
            setLocal = { state.householdName = it },
            onIdChanged = {
                router?.onboardingCompleted(it)
                householdId = it
            },
        )
    }
    val members = remember(api) {
        MemberSyncController(
            scope = scope,
            listMembers = { id ->
                api.listPeople(id).filter { !it.isArchived }.map { HouseholdMember(it.id, it.displayName) }
            },
            renameMember = { id, personId, name ->
                api.renamePerson(id, personId, name).let { HouseholdMember(it.id, it.displayName, it.isArchived) }
            },
            archiveMember = { id, personId -> api.archivePerson(id, personId) },
            applyMembers = { loaded ->
                state.loadMembers(loaded)
                val id = (householdId ?: router?.currentHouseholdId())?.takeIf { it != LOCAL_HOUSEHOLD_ID }
                if (id != null) {
                    val savedMe = memberStore.get(id)
                    state.setMe(savedMe?.takeIf { personId -> loaded.any { it.id == personId } } ?: state.meId)
                    memberStore.save(id, state.meId)
                }
            },
            applyName = state::renamePerson,
        )
    }
    val historyState by history.state.collectAsState()
    val historyScroll = rememberScrollState()
    var historyFrom by remember { mutableStateOf("") }
    var historyTo by remember { mutableStateOf("") }
    fun pushHistoryDates(from: String, to: String) {
        val fromIso = parseDateInput(from)
        val toIso = parseDateInput(to)
        if ((from.isBlank() || fromIso != null) && (to.isBlank() || toIso != null)) {
            history.setDates(fromIso.takeIf { from.isNotBlank() }, toIso.takeIf { to.isNotBlank() })
        }
    }

    val detailState by detail.state.collectAsState()
    val autoState by autoAssign.state.collectAsState()
    var viewedPhoto by remember { mutableStateOf<ImageBitmap?>(null) }
    fun activeHousehold(): String? =
        router?.currentHouseholdId()?.takeIf { it.isNotBlank() && it != LOCAL_HOUSEHOLD_ID }
    LaunchedEffect(route) {
        val household = activeHousehold() ?: return@LaunchedEffect
        when (route) {
            Route.History -> {
                history.load(household)
                nameSync.open(household)
                members.load(household)
            }

            Route.Account -> {
                autoAssign.load(household)
                nameSync.open(household)
                members.load(household)
            }

            else -> Unit
        }
    }
    LaunchedEffect(autoState.autoAssign, autoState.threshold) {
        val on = autoState.autoAssign
        val threshold = autoState.threshold
        if (on != null && threshold != null) state.settings =
            state.settings.copy(autoAssign = on, autoAssignThreshold = threshold)
    }
    viewedPhoto?.let { photo ->
        Dialog(onDismissRequest = { viewedPhoto = null }) {
            Image(
                photo,
                contentDescription = "Receipt photo",
                modifier = Modifier.fillMaxWidth(),
                contentScale = ContentScale.Fit
            )
        }
    }
    val balancesController = remember(api) {
        val balanceRepository = BalanceRepository(api)
        BalancesController(
            load = balanceRepository::balances,
            scope = scope,
            record = balanceRepository::settle,
            currentPerson = memberStore::get,
            saveVenmo = HouseholdRepository(api)::setVenmoUsername,
        )
    }
    val balancesState by balancesController.state.collectAsState()
    val urlOpener = rememberUrlOpener()
    val venmoIds = remember { VenmoUserIds() }
    val serverShopping = remember(api) {
        ShoppingListController(
            RepositoryShoppingListApi(ShoppingListRepository(api)),
            scope,
            // Only a member loaded from the server can be named as who added an item.
            currentPerson = { state.meId.takeIf { id -> serverId.matches(id) && state.people.any { it.id == id } } },
        )
    }
    val localShopping = remember {
        ShoppingListController(
            LocalShoppingListApi(SampleData.shopping, sampleGroceries, nameOf = { state.person(it)?.name }),
            scope,
            currentPerson = { state.meId },
        )
    }
    val serverShoppingState by serverShopping.state.collectAsState()
    val localShoppingState by localShopping.state.collectAsState()
    var shoppingItemId by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(reviewState.phase) {
        if (reviewState.phase == ReviewPhase.Confirmed) {
            review.close()
            route = Route.Balances
        }
    }
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
                    api.createHousehold(state.householdName).name.also { router?.onboardingCompleted(it) }

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
                reviewHouseholdId = householdId
                review.open(householdId, uploaded.id, payerStore.get(householdId))
                selectedPhoto = null
                photoStage = ReceiptPhotoStage.Empty
                uploadProgress = null
                route = Route.Review
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
        onError = {
            cameraError = "Camera unavailable. Check camera access in Settings, or choose a photo from Gallery."
        },
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
                            route = if (form.joiningExisting) Route.JoinHousehold else Route.AddMembers
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
                            val personId = form.meId ?: return@launch
                            memberStore.save(householdId.orEmpty(), personId)
                            state.setMe(personId)
                            router?.onboardingCompleted(householdId.orEmpty())
                            route = Route.History
                        }
                    },
                )
            }

            Route.JoinHousehold -> {
                val id = householdId.orEmpty()
                val form = remember(id, loadHouseholdMembers) { JoinHouseholdState(id, loadHouseholdMembers) }
                LaunchedEffect(form) { form.load() }
                JoinHouseholdScreen(
                    state = form,
                    onRetry = { scope.launch { form.load() } },
                    onContinue = {
                        val personId = form.selectedId ?: return@JoinHouseholdScreen
                        memberStore.save(id, personId)
                        state.setMe(personId)
                        router?.onboardingCompleted(id)
                        route = Route.History
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

            Route.Review -> {
                val live = reviewState.draft
                when {
                    reviewState.phase == ReviewPhase.Idle -> ReviewReceiptScreen(
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

                    live != null && (reviewState.phase == ReviewPhase.Ready || reviewState.phase == ReviewPhase.Confirming) ->
                        ReviewReceiptScreen(
                            draft = live,
                            autoExpanded = state.autoExpanded,
                            onToggleOwner = review::toggleOwner,
                            onConfirmLine = review::accept,
                            onToggleAuto = state::toggleAuto,
                            onConfirmSplit = review::confirm,
                            onTab = onTab,
                            onEditLine = review::editLine,
                            onAddLine = review::addLine,
                            onDeleteLine = review::deleteLine,
                            onMergeLines = review::mergeLines,
                            canUndo = reviewState.canUndo,
                            onUndo = review::undoDelete,
                            notice = reviewState.message ?: reviewState.warning,
                            onChoosePayer = review::setPayer,
                            onEditDetails = { store, date, tax, total ->
                                review.editDetails(ReceiptDetailsEdit(store, date, tax, total))
                            },
                        )

                    else -> ReviewStatusScreen(
                        title = when (reviewState.phase) {
                            ReviewPhase.Reading -> "Reading your receipt"
                            ReviewPhase.Failed -> "Could not read the receipt"
                            else -> "Could not open the receipt"
                        },
                        message = if (reviewState.phase == ReviewPhase.Reading) {
                            "This usually takes under a minute."
                        } else reviewState.message,
                        busy = reviewState.phase == ReviewPhase.Reading,
                        actionLabel = when (reviewState.phase) {
                            ReviewPhase.Failed -> "Try again"
                            ReviewPhase.Unreachable -> "Check again"
                            else -> null
                        },
                        onAction = review::retry,
                        onBack = {
                            review.close()
                            route = Route.History
                        },
                        onTab = onTab,
                    )
                }
            }

            Route.History -> {
                val household = activeHousehold()
                if (household == null) {
                    ReceiptHistoryScreen(
                        entries = state.history,
                        onOpen = {
                            route =
                                if (it == 0 && state.history[0].status == ReceiptStatus.NeedsReview) Route.Review else Route.Detail
                        },
                        onScan = { route = Route.Camera },
                        onTab = onTab,
                    )
                } else {
                    val rows = historyState.rows
                    ReceiptHistoryScreen(
                        entries = rows.map { it.entry },
                        onOpen = { index ->
                            val row = rows.getOrNull(index) ?: return@ReceiptHistoryScreen
                            when (row.status) {
                                ReceiptStatusDto.Confirmed -> {
                                    detail.open(household, row.id)
                                    route = Route.Detail
                                }

                                ReceiptStatusDto.Void -> Unit
                                else -> {
                                    review.open(household, row.id)
                                    route = Route.Review
                                }
                            }
                        },
                        onScan = { route = Route.Camera },
                        onTab = onTab,
                        message = historyState.message ?: if (historyState.loaded && rows.isEmpty()) {
                            if (historyState.filter.active) "No receipts match these filters." else "No receipts yet. Scan one to get started."
                        } else null,
                        scrollState = historyScroll,
                                                filters = HistoryFilters(
                            store = historyState.filter.store,
                            status = historyState.filter.status,
                            fromIso = historyState.filter.fromIso,
                            toIso = historyState.filter.toIso,
                            active = historyState.filter.active,
                            hasMore = historyState.hasMore,
                            loadingMore = historyState.loadingMore,
                            loadFailed = historyState.message != null,
                            onStore = history::setStore,
                            onStatus = history::setStatus,
                            onFrom = { history.setDates(it, historyState.filter.toIso) },
                            onTo = { history.setDates(historyState.filter.fromIso, it) },
                            onClear = history::clearFilters,
                            onLoadMore = history::loadMore,
                        ),
                    )
                }
            }

            Route.Detail -> {
                val household = activeHousehold()
                val shown = detailState.receipt
                when {
                    detailState.phase == DetailPhase.Idle -> ReceiptDetailScreen(
                        receipt = state.resolvedDraft,
                        shares = state.shares,
                        onViewPhoto = {},
                        onReopen = {},
                        onTab = onTab,
                    )

                    shown != null && detailState.phase == DetailPhase.Ready -> ReceiptDetailScreen(
                        receipt = shown,
                        shares = detailState.shares,
                        onViewPhoto = {
                            if (detailState.hasPhoto) scope.launch {
                                detail.photo()?.let { bytes -> viewedPhoto = bytes.decodeToImageBitmap() }
                            }
                        },
                        onReopen = {
                            detail.reopen { id ->
                                if (household != null) review.open(household, id)
                                route = Route.Review
                            }
                        },
                        onTab = onTab,
                        notice = detailState.message,
                    )

                    else -> ReviewStatusScreen(
                        title = if (detailState.phase == DetailPhase.Loading) "Opening receipt" else "Could not open the receipt",
                        message = detailState.message,
                        busy = detailState.phase == DetailPhase.Loading,
                        actionLabel = null,
                        onAction = {},
                        onBack = {
                            detail.close()
                            route = Route.History
                        },
                        onTab = onTab,
                    )
                }
            }

            Route.Balances -> {
                val id = householdId ?: router?.currentHouseholdId()
                val real = id != null && id != LOCAL_HOUSEHOLD_ID
                LaunchedEffect(id) {
                    if (real) {
                        balancesController.open(id!!)
                        // Works out who "you" are on this device, which decides where Venmo is offered.
                        members.load(id)
                    }
                }
                LiveBalancesScreen(
                    balancesState,
                    hasHousehold = real,
                    onRefresh = balancesController::refresh,
                    onTab = onTab,
                    onSettle = balancesController::settle,
                    onUndo = balancesController::undo,
                    onPayWithVenmo = { transfer ->
                        transfer.toVenmo?.let { username ->
                            scope.launch {
                                urlOpener.payWithVenmo(username, transfer.amountCents.toLong(), VENMO_NOTE, venmoIds.idFor(username))
                            }
                        }
                    },
                    onRequestWithVenmo = { transfer ->
                        transfer.fromVenmo?.let { username ->
                            scope.launch {
                                urlOpener.requestWithVenmo(username, transfer.amountCents.toLong(), VENMO_NOTE, venmoIds.idFor(username))
                            }
                        }
                    },
                    onSaveVenmo = balancesController::setVenmo,
                    onEditVenmo = balancesController::dismissVenmoMessage,
                    // The same "(you)" as the Account screen, once members have loaded from the server.
                    meId = state.meId.takeIf { me -> state.people.any { it.id == me } && serverId.matches(me) },
                )
            }

            Route.Shopping -> {
                val household = activeHousehold()
                val list = if (household != null) serverShopping else localShopping
                LaunchedEffect(household) {
                    list.open(household ?: LOCAL_HOUSEHOLD_ID)
                    // Loads "me", who is recorded as having added items.
                    if (household != null) members.load(household)
                }
                ShoppingListScreen(
                    state = if (household != null) serverShoppingState else localShoppingState,
                    suggestions = state.suggestions,
                    onInput = list::type,
                    onAdd = { list.add(it) },
                    onToggle = list::toggle,
                    onOpen = {
                        list.dismissMessage()
                        shoppingItemId = it
                        route = Route.ShoppingItem
                    },
                    onRemove = { list.remove(it, offerUndo = true) },
                    onUndoRemove = list::undoRemove,
                    onAddSuggestion = { i -> state.addSuggestion(i)?.let { list.add(it) } },
                    onRetry = list::refresh,
                    onDismissMessage = list::dismissMessage,
                    onTab = onTab,
                )
            }

            Route.ShoppingItem -> {
                val household = activeHousehold()
                val list = if (household != null) serverShopping else localShopping
                val listState = if (household != null) serverShoppingState else localShoppingState
                val item = listState.items.find { it.id == shoppingItemId }
                if (item == null) {
                    // Removed, here or by someone else.
                    LaunchedEffect(Unit) { route = Route.Shopping }
                } else {
                    ShoppingItemScreen(
                        item = item,
                        saving = listState.saving,
                        message = listState.message,
                        onSave = { name, quantity, notes ->
                            list.save(item.id, name, quantity, notes) {
                                route = Route.Shopping
                            }
                        },
                        onToggle = { list.toggle(item.id) },
                        onRemove = { list.remove(item.id) },
                        onTab = onTab,
                    )
                }
            }

            Route.Account -> AccountScreen(
                me = state.me,
                householdName = state.householdName,
                people = state.people,
                settings = state.settings,
                onHouseholdNameChange = nameSync::edit,
                householdNameMessage = nameSync.message,
                onEditPerson = {
                    editingId = it
                    route = Route.EditPerson
                },
                onAddMember = {
                    if (activeHousehold() != null) {
                        route = Route.HouseholdSettings
                    } else {
                        editingId = state.addPerson("New member")
                        route = Route.EditPerson
                    }
                },
                onSettingsChange = { updated ->
                    val before = state.settings
                    state.settings = updated
                    if (updated.autoAssign != before.autoAssign || updated.autoAssignThreshold != before.autoAssignThreshold) {
                        autoAssign.change(updated.autoAssign, updated.autoAssignThreshold)
                    }
                },
                settingsMessage = autoState.message,
                apiConfig = apiConfig,
                onApiConfigChange = onApiConfigChange,
                onTab = onTab,
                onOpenHouseholdSettings = { route = Route.HouseholdSettings },
            )

            Route.HouseholdSettings -> {
                val id = householdId ?: router?.currentHouseholdId()
                val form = remember {
                    HouseholdSettingsState(
                        load = {
                            if (id == null || settingsApi == null) SettingsResult.Failure("Not connected")
                            else settingsApi.load(id)
                        },
                        rename = { name ->
                            if (id == null || settingsApi == null) SettingsResult.Failure("Not connected")
                            else settingsApi.rename(id, name)
                        },
                        create = { name -> createPerson(id.orEmpty(), name) },
                        archive = { personId ->
                            if (id == null || settingsApi == null) SettingsResult.Failure("Not connected")
                            else settingsApi.archive(id, personId)
                        },
                        setVenmo = { personId, username ->
                            if (id == null || settingsApi == null) SettingsResult.Failure("Not connected")
                            else settingsApi.setVenmo(id, personId, username)
                        },
                    )
                }
                HouseholdSettingsScreen(
                    state = form,
                    onBack = { route = Route.Account },
                    onTab = onTab,
                )
            }

            Route.EditPerson -> state.person(editingId)?.let { person ->
                val household = activeHousehold()
                EditPersonScreen(
                    person = person,
                    isMe = person.id == state.meId,
                    canRemove = state.canRemove(person.id) && !members.busy,
                    onChange = { updated ->
                        state.updatePerson(updated)
                        if (household != null && updated.name != person.name) members.rename(updated.id, updated.name)
                    },
                    onRemove = {
                        if (household == null) {
                            route = Route.Account
                            state.removePerson(person.id)
                        } else {
                            members.archive(person.id) { route = Route.Account }
                        }
                    },
                    onDone = { route = Route.Account },
                    onTab = onTab,
                    nameMessage = members.message,
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
