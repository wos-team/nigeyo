package jp.nigeyo.app

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.core.content.edit
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val training get() = (application as NigeyoApplication).training
    private var pendingDrill: Scenario? = null
    private var pendingWallpaper = false
    private var evacuationRequest by mutableStateOf<String?>(null)
    private val requestNotifications = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        pendingDrill?.let { training.start(it, pendingWallpaper) }
        pendingDrill = null
        pendingWallpaper = false
        training.refresh()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pendingDrill = savedInstanceState?.getString("pendingDrill")?.let(Scenario::valueOf)
        pendingWallpaper = savedInstanceState?.getBoolean("pendingWallpaper") == true
        val bars = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
        enableEdgeToEdge(statusBarStyle = bars, navigationBarStyle = bars)
        training.refresh()
        readGuideIntent(intent)
        setContent {
            MaterialTheme(colorScheme = lightColorScheme(primary = Ink, background = Paper)) {
                NigeyoApp(training, ::startDrill, ::requestNotificationAccess, evacuationRequest) {
                    evacuationRequest = null
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        training.refresh()
        training.demo.resume()
        training.refreshRegionalMonitor()
    }

    override fun onPause() {
        training.demo.foreground = false
        super.onPause()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        readGuideIntent(intent)
    }

    private fun readGuideIntent(intent: Intent) {
        if (intent.action == OPEN_EVACUATION) evacuationRequest = intent.getStringExtra(GUIDE_SESSION_ID)
    }

    private fun requestNotificationAccess() {
        if (Build.VERSION.SDK_INT >= 33) requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("pendingDrill", pendingDrill?.name)
        outState.putBoolean("pendingWallpaper", pendingWallpaper)
        super.onSaveInstanceState(outState)
    }

    private fun startDrill(scenario: Scenario, changeWallpaper: Boolean) {
        val preferences = getPreferences(Activity.MODE_PRIVATE)
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
            !preferences.getBoolean("askedForNotifications", false)
        ) {
            pendingDrill = scenario
            pendingWallpaper = changeWallpaper
            preferences.edit { putBoolean("askedForNotifications", true) }
            requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            training.start(scenario, changeWallpaper)
        }
    }
}

internal enum class Page {
    Home, Practice, Settings, Language, Sites, SetupLanguage, SetupSites, SetupRegions, SetupLockScreen,
    LockScreen, Privacy, Delete, Diagnostics, Demo, LanguageUpdate, Regions,
}

@Composable
private fun NigeyoApp(
    training: TrainingController,
    startDrill: (Scenario, Boolean) -> Unit,
    requestNotifications: () -> Unit,
    evacuationRequest: String?,
    consumeEvacuationRequest: () -> Unit,
) {
    var page by rememberSaveable {
        mutableStateOf(if (training.onboardingDone) Page.Home else Page.SetupLanguage)
    }
    var scenario by rememberSaveable { mutableStateOf(Scenario.Earthquake) }
    var changeWallpaper by rememberSaveable { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val session = training.session
    var confirmingEnd by rememberSaveable(session?.id) { mutableStateOf(false) }
    val phase = training.phase
    val hazard = if (phase == GuidePhase.Tsunami) Hazard.Tsunami else Hazard.Earthquake
    var choosingSite by rememberSaveable(session?.id, hazard) { mutableStateOf(false) }
    val strings = session?.let { training.content.language(it.languageId) } ?: training.language
    LaunchedEffect(evacuationRequest) {
        if (evacuationRequest == null) return@LaunchedEffect
        if (session?.id == evacuationRequest && (phase == GuidePhase.Checks || phase == GuidePhase.Tsunami)) {
            choosingSite = true
        }
        consumeEvacuationRequest()
    }
    val back: () -> Unit = {
        when {
            session != null && phase == null -> training.end()
            session != null -> confirmingEnd = true
            page == Page.Language -> page = Page.Settings
            page == Page.SetupSites -> page = Page.SetupLanguage
            page == Page.SetupRegions -> page = Page.SetupSites
            page == Page.SetupLockScreen -> page = Page.SetupRegions
            page in setOf(Page.LockScreen, Page.Privacy, Page.Delete, Page.Diagnostics,
                Page.Demo, Page.LanguageUpdate, Page.Regions) -> page = Page.Settings
            else -> page = Page.Home
        }
    }

    BackHandler(enabled = session != null || page !in setOf(Page.Home, Page.SetupLanguage), onBack = back)

    CompositionLocalProvider(
        LocalLayoutDirection provides if (strings.isRtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
    ) {
        key(page, session?.id, phase) {
            when {
                session != null && phase == null ->
                    WaitingScreen(strings, training.notificationsEnabled, training::end)
                session != null && phase != null && (choosingSite || session.destination != null) ->
                    EvacuationFlow(strings, training, hazard, session, { choosingSite = false }) {
                        confirmingEnd = true
                    }
                session != null && phase != null -> GuideScreen(
                    strings, phase, training.content.cards(phase), session.isTraining,
                    training.wallpaperUpdateFailed,
                    { training.confirmShakingStopped() },
                    { choosingSite = true },
                ) { confirmingEnd = true }
                else -> when (page) {
                    Page.Home -> HomeScreen(
                        strings = strings,
                        languages = training.content.languages,
                        selectLanguage = training::chooseLanguage,
                        settings = { page = Page.Settings },
                        practice = {
                            scenario = Scenario.Earthquake
                            changeWallpaper = false
                            page = Page.Practice
                        },
                        manual = { training.start() },
                        sites = { page = Page.Sites },
                    )
                    Page.Practice -> PracticeScreen(
                        strings, scenario, { scenario = it }, changeWallpaper,
                        { changeWallpaper = it }, back,
                    ) {
                        page = Page.Home
                        startDrill(scenario, changeWallpaper)
                    }
                    Page.Settings -> SettingsScreen(strings, back) { page = it }
                    Page.Language -> LanguageScreen(
                        strings, training.content.languages, training::chooseLanguage, back,
                    )
                    Page.Sites -> SavedPlacesScreen(strings, training, back)
                    Page.SetupLanguage -> LanguageScreen(
                        strings, training.content.languages, training::chooseLanguage, back,
                        next = { page = Page.SetupSites },
                    )
                    Page.SetupSites -> SavedPlacesScreen(strings, training, back, next = { page = Page.SetupRegions })
                    Page.SetupRegions -> NotificationRegionsScreen(
                        strings, training, back, requestNotifications, next = { page = Page.SetupLockScreen },
                    )
                    Page.SetupLockScreen -> LockScreenSettings(
                        strings, training, back, requestNotifications,
                        finish = {
                            training.finishOnboarding()
                            page = Page.Home
                        },
                    )
                    Page.LockScreen -> LockScreenSettings(
                        strings, training, back, requestNotifications,
                        test = { page = Page.Practice },
                    )
                    Page.Privacy -> PrivacyScreen(strings, back)
                    Page.Delete -> DeleteScreen(strings, back) {
                        scope.launch {
                            training.erasePreferences()
                            if (!training.onboardingDone) page = Page.SetupLanguage
                        }
                    }
                    Page.Diagnostics -> DiagnosticScreen(strings, training, back)
                    Page.Demo -> DemoScreen(strings, training.demo, back)
                    Page.LanguageUpdate -> LanguageUpdateScreen(strings, training, back)
                    Page.Regions -> NotificationRegionsScreen(strings, training, back, requestNotifications)
                }
            }
        }

        if (session != null && confirmingEnd) {
            AlertDialog(
                onDismissRequest = { confirmingEnd = false },
                title = { Text(strings[if (session.isTraining) "endConfirm.title" else "endConfirm.titleManual"]) },
                text = { Text(strings[if (session.isTraining) "android.endBody" else "android.endBodyManual"]) },
                confirmButton = {
                    TextButton(onClick = {
                        confirmingEnd = false
                        choosingSite = false
                        training.end()
                        if (training.session == null && training.onboardingDone) page = Page.Home
                    }) {
                        Text(strings[if (session.isTraining) "endConfirm.end" else "endConfirm.endManual"])
                    }
                },
                dismissButton = {
                    TextButton(onClick = { confirmingEnd = false }) { Text(strings["endConfirm.keep"]) }
                },
            )
        }

        if (training.storageError) {
            AlertDialog(
                onDismissRequest = training::dismissStorageError,
                text = { Text(strings["android.storageError"]) },
                confirmButton = {
                    TextButton(onClick = training::dismissStorageError) { Text(strings["android.back"]) }
                },
            )
        }
    }
}
