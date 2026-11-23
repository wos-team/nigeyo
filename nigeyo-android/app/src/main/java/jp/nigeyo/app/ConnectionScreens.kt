package jp.nigeyo.app

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.PasswordVisualTransformation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun DemoScreen(strings: Language, demo: DemoConnection, back: () -> Unit) {
    var url by remember { mutableStateOf("") }
    var invalid by remember { mutableStateOf(false) }
    Screen(navTitle = strings["demo.nav"], backLabel = strings["android.back"], onBack = back) {
        Title(strings["demo.eyebrow"])
        Panel { RowLabels(strings["demo.state.${demo.state}"], demo.host ?: strings["demo.body"]) }
        OutlinedTextField(url, { url = it; invalid = false }, Modifier.fillMaxWidth(), singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            label = { Label(strings["demo.urlLabel"]) },
        )
        if (invalid) Label(strings["demo.invalidURL"], color = Muted)
        if (url.isNotBlank()) ActionButton(strings["demo.connect"]) {
            invalid = !demo.connect(url)
            if (!invalid) url = ""
        }
        if (demo.state != "disconnected") {
            ActionButton(strings["demo.disconnect"], background = Color.White, onClick = demo::disconnect)
        }
        Label(strings["demo.note"], color = Muted)
    }
}

@Composable
internal fun LanguageUpdateScreen(strings: Language, controller: TrainingController, back: () -> Unit) {
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    BackHandler(enabled = busy) {}
    Screen(navTitle = strings["languageUpdate.nav"], backLabel = strings["android.back"],
        onBack = { if (!busy) back() },
    ) {
        Label(strings["languageUpdate.body"])
        controller.content.languages.forEach {
            Panel { RowLabels(it.nativeName, "v${it.version}") }
        }
        if (busy) CircularProgressIndicator()
        message?.let { Label(it, color = Muted) }
        if (!busy) ActionButton(strings["languageUpdate.run"]) {
            val origin = controller.demo.origin
            if (origin == null) {
                message = strings["languageUpdate.noOrigin"]
            } else if (controller.session != null) {
                message = strings["languageUpdate.busy"]
            } else scope.launch {
                busy = true
                try {
                    val (added, updated) = controller.content.download(origin)
                    if (controller.session == null) controller.reloadLanguages()
                    message = strings.format("languageUpdate.result",
                        "added" to added.toString(), "updated" to updated.toString())
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    message = strings["languageUpdate.failed"]
                } finally {
                    busy = false
                }
            }
        }
    }
}

@Composable
internal fun NotificationRegionsScreen(
    strings: Language,
    controller: TrainingController,
    back: () -> Unit,
    requestNotifications: () -> Unit,
    next: (() -> Unit)? = null,
) {
    val alerts = controller.alerts
    val scope = rememberCoroutineScope()
    var place by remember { mutableStateOf<Place?>(null) }
    var choosingTsunami by remember { mutableStateOf(false) }
    var quake by remember { mutableStateOf<NotificationArea?>(null) }
    var query by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var suggestions by remember { mutableStateOf<RegionSuggestions?>(null) }
    var map by remember { mutableStateOf(false) }
    val configuring = place
    val goBack: () -> Unit = {
        if (!busy) {
            if (configuring == null) back()
            else if (choosingTsunami) {
                choosingTsunami = false
                query = ""
            } else {
                place = null
            }
        }
    }
    BackHandler(enabled = configuring != null || busy, onBack = goBack)
    if (configuring != null) {
        val title = strings[if (choosingTsunami) "regions.tsunami" else "regions.earthquake"]
        val choices = if (choosingTsunami) alerts.tsunamis else alerts.earthquakes
        fun confirm(area: NotificationArea?) {
            val selected = quake ?: return
            alerts.setAreas(configuring, RegisteredAreas(selected.id, area?.id))
            place = null
            choosingTsunami = false
        }
        Screen(navTitle = title, backLabel = strings["android.back"], onBack = goBack) {
            Title(configuring.title(strings))
            OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), singleLine = true,
                label = { Label(strings["android.searchRegion"]) },
            )
            if (!choosingTsunami && !busy) {
                ActionButton(strings["onboarding.places.pinTitle"], background = Color.White) { map = true }
            }
            if (busy) CircularProgressIndicator()
            message?.let { Label(it, color = Muted) }
            if (choosingTsunami) ActionButton(strings["android.noTsunamiRegion"], background = Color.White) {
                confirm(null)
            }
            val suggested = if (choosingTsunami) suggestions?.tsunami else suggestions?.earthquake
            if (!suggested.isNullOrEmpty()) Eyebrow(strings["android.regionSuggestions"])
            choices.asSequence()
                .filter { it.label.contains(query.trim(), ignoreCase = true) }
                .sortedBy { it.id !in suggested.orEmpty() }.take(30).forEach { area ->
                    Panel(background = if (area.id in suggested.orEmpty()) Peach else Color.White, onClick = {
                        if (choosingTsunami) confirm(area)
                        else {
                            quake = area
                            choosingTsunami = true
                            query = ""
                        }
                    }) { Label(area.label) }
                }
        }
        if (map) PlaceMapDialog(strings, null, { map = false }) { coordinate ->
            map = false
            scope.launch {
                busy = true
                message = null
                try {
                    suggestions = alerts.suggest(coordinate)
                    if (suggestions?.earthquake.isNullOrEmpty()) message = strings["regions.noneNote"]
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    message = strings["error.generic"]
                } finally {
                    busy = false
                }
            }
        }
        return
    }

    fun update(enabled: Boolean) = scope.launch {
        busy = true
        message = null
        try {
            alerts.setEnabled(enabled, strings.id)
            controller.demo.hello()
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            message = strings["regions.registration.failed"]
        } finally {
            controller.refreshRegionalMonitor()
            busy = false
        }
    }

    Screen(navTitle = strings["regions.title"], backLabel = strings["android.back"], onBack = goBack,
        actions = next?.let { callback -> { if (!busy) ActionButton(strings["common.continue"], onClick = callback) } },
    ) {
        Label(strings["android.regionsBody"])
        if (!alerts.cloudConfigured) Label(strings["regions.registration.unavailable"], color = Muted)
        Place.entries.forEach { item ->
            val selected = alerts.regions[item]
            val earthquake = alerts.earthquakes.firstOrNull { it.id == selected?.quake }
            val tsunami = alerts.tsunamis.firstOrNull { it.id == selected?.tsunami }
            Panel(onClick = {
                if (!busy && !alerts.enabled) {
                    place = item
                    query = ""
                    suggestions = null
                    message = null
                }
            }.takeIf { !alerts.enabled && !busy }) {
                RowLabels(item.title(strings), earthquake?.label ?: strings["regions.unconfigured"])
                if (earthquake != null) {
                    Label(tsunami?.label ?: strings["regions.none"], color = Muted)
                    if (!alerts.enabled && !busy) {
                        ActionButton(strings["android.removeRegion"], background = Color.White) { alerts.setAreas(item, null) }
                    }
                }
            }
        }
        if (busy) CircularProgressIndicator()
        if (alerts.enabled) {
            if (alerts.cloudConfigured) Label(strings[if (alerts.synced) {
                "regions.registration.registered"
            } else "regions.registration.unsynced"])
            if (!busy) ActionButton(strings["regions.registration.revoke"]) { update(false) }
        }
        if (!busy && !alerts.enabled && alerts.needsRevocation) {
            ActionButton(strings["regions.registration.revoke"]) { update(false) }
        }
        if (alerts.cloudConfigured) Label(strings["android.regionalMonitorNote"], color = Muted)
        if (!busy && alerts.regions.isNotEmpty()) {
            if (controller.notificationsEnabled) {
                if (!alerts.enabled || (alerts.cloudConfigured && !alerts.synced)) {
                    val key = if (alerts.enabled) "regions.registration.retry" else "regions.enabled"
                    ActionButton(strings[key]) { update(true) }
                }
            } else {
                ActionButton(strings["android.allowNotifications"], onClick = requestNotifications)
            }
        }
        message?.let { Label(it, color = Muted) }
        if (alerts.latest.isNotEmpty()) {
            Title(strings["regions.latest"], size = 17)
            alerts.latest.forEach { (hazard, event) ->
                Panel {
                    RowLabels(strings["hazard.${hazard.wireName}"],
                        strings["regions.status.${event.getString("status")}"])
                    Label(event.optString("issuedAt"), color = Muted)
                }
            }
        }
    }
}
