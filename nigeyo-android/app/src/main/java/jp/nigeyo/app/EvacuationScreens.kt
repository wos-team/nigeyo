package jp.nigeyo.app

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.text.NumberFormat
import java.util.Locale

@Composable
internal fun SavedPlacesScreen(
    strings: Language,
    controller: TrainingController,
    back: () -> Unit,
    next: (() -> Unit)? = null,
) {
    var slot by rememberSaveable { mutableStateOf<String?>(null) }
    val editing = SiteSlot.all.firstOrNull { it.key == slot }
    val returnToList = { slot = null }
    BackHandler(enabled = editing != null, onBack = returnToList)
    if (editing != null) {
        SearchSitesScreen(strings, controller.sites, editing.hazard, editing.label(strings), returnToList,
            onChoose = {
                controller.saveSite(editing, it)
                returnToList()
            },
            onClear = {
                controller.saveSite(editing, null)
                returnToList()
            },
        )
        return
    }
    Screen(navTitle = strings["savedPlaces.nav"], backLabel = strings["android.back"], onBack = back,
        actions = next?.let { action -> { ActionButton(strings["common.continue"], onClick = action) } },
    ) {
        Label(strings["onboarding.places.privacyBody"], color = Muted)
        SiteSlot.all.forEach { key ->
            val candidate = controller.savedSites[key.key]
            Panel(onClick = { slot = key.key }) {
                RowLabels(key.label(strings), candidate?.name ?: strings["onboarding.destinations.none"])
                candidate?.let { Label(it.address, color = Muted) }
            }
        }
        DataSource(strings)
    }
}

@Composable
internal fun EvacuationFlow(
    strings: Language,
    controller: TrainingController,
    hazard: Hazard,
    session: GuideSession,
    back: () -> Unit,
    confirmEnd: () -> Unit,
) {
    var search by rememberSaveable { mutableStateOf(false) }
    var missing by rememberSaveable { mutableStateOf(false) }
    val site = session.destination?.takeIf(hazard::supports)
    val goBack = {
        when {
            site != null -> controller.chooseSite(null)
            search || missing -> {
                search = false
                missing = false
            }
            else -> back()
        }
    }
    BackHandler(onBack = goBack)
    when {
        site != null -> DestinationScreen(strings, session, site, goBack, controller::confirmEvacuation, confirmEnd)
        search -> SearchSitesScreen(
            strings, controller.sites, hazard, strings["place.other"], goBack,
            onChoose = { controller.chooseSite(it) },
        )
        else -> Screen(navTitle = strings["place.nav"], backLabel = strings["android.back"], onBack = goBack) {
            if (session.isTraining) Badge(strings["training.badgeLong"])
            Title(strings["place.title"])
            Label(strings[if (hazard == Hazard.Tsunami) "place.bodyTsunami" else "place.body"])
            Place.entries.forEach { place ->
                val saved = controller.savedSites[SiteSlot(place, hazard).key]
                Panel(onClick = {
                    if (saved != null) controller.chooseSite(saved)
                    else missing = true
                }) {
                    RowLabels(place.title(strings), saved?.name ?: strings["onboarding.destinations.none"])
                }
            }
            if (missing) Label(strings["place.notSaved"], color = Muted)
            Panel(Peach, onClick = { search = true }) {
                RowLabels(strings["place.other"], strings["place.otherBody"])
            }
            if (hazard == Hazard.Tsunami) Label(strings["place.tsunamiNote"], color = Muted)
        }
    }
}

@Composable
private fun SearchSitesScreen(
    strings: Language,
    sites: EvacuationSites,
    hazard: Hazard,
    title: String,
    back: () -> Unit,
    onChoose: (EvacuationSite) -> Unit,
    onClear: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var lat by remember { mutableStateOf("") }
    var lon by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var mapOpen by remember { mutableStateOf(false) }
    var mapCenter by remember { mutableStateOf<Coordinates?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var addresses by remember { mutableStateOf<List<Pair<String, Coordinates>>>(emptyList()) }
    var results by remember { mutableStateOf<List<SiteDistance>>(emptyList()) }

    fun search(location: Coordinates?, accuracy: Float? = null) = scope.launch {
        if (location?.isValid != true) {
            message = strings["locationError.title"]
            return@launch
        }
        busy = true
        mapCenter = location
        results = emptyList()
        addresses = emptyList()
        message = accuracy?.takeIf { it > 1000 }?.let {
            strings.format("candidates.lowAccuracy", "accuracy" to distance(strings, it))
        }
        try {
            results = sites.nearest(location, hazard, if (onClear == null) 2 else 5)
            if (results.isEmpty()) message = strings["onboarding.destinations.none"]
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            message = strings["error.generic"]
        } finally {
            busy = false
        }
    }

    fun locate() = scope.launch {
        busy = true
        try {
            val location = locateOnce(context)
            search(location?.let { Coordinates(it.latitude, it.longitude) }, location?.accuracy)
        } finally {
            busy = false
        }
    }

    val requestLocation = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { permissions ->
        if (permissions.values.any { it }) locate()
        else message = strings["locationError.title"]
    }

    Screen(navTitle = title, backLabel = strings["android.back"], onBack = back) {
        Badge(strings["hazard.${hazard.wireName}Supported"])
        if (busy) CircularProgressIndicator()
        message?.let { Label(it, color = Muted) }
        results.forEach { candidate ->
            Panel(onClick = { onChoose(candidate.site) }) {
                RowLabels(candidate.site.name, candidate.site.address)
                Label(strings.format("distance.straight", "distance" to distance(strings, candidate.meters)))
            }
        }
        if (results.isNotEmpty()) Label(strings["candidates.note"], color = Muted)
        if (!busy) {
            ActionButton(strings["android.useCurrentLocation"], background = Color.White) {
                val permissions = arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
                if (permissions.any { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }) locate()
                else requestLocation.launch(permissions)
            }
            OutlinedTextField(
                query, { query = it }, Modifier.fillMaxWidth(), singleLine = true,
                label = { Label(strings["onboarding.places.searchHint"]) },
            )
            if (query.isNotBlank()) ActionButton(strings["android.searchAddress"], background = Color.White) {
                scope.launch {
                    busy = true
                    message = null
                    addresses = emptyList()
                    results = emptyList()
                    try {
                        addresses = geocodePlace(context, query.trim(), strings.id)
                        if (addresses.isEmpty()) message = strings["android.searchEmpty"]
                    } catch (error: Exception) {
                        if (error is CancellationException) throw error
                        message = strings["android.searchEmpty"]
                    } finally {
                        busy = false
                    }
                }
            }
            addresses.forEach { (address, coordinate) ->
                Panel(onClick = { search(coordinate) }) { Label(address, bold = true) }
            }
            ActionButton(strings["onboarding.places.pinTitle"], background = Color.White) { mapOpen = true }
            Panel {
                Label(strings["android.coordinates"], bold = true)
                OutlinedTextField(lat, { lat = it }, Modifier.fillMaxWidth(), singleLine = true, label = {
                    Label(strings["android.latitude"])
                })
                OutlinedTextField(lon, { lon = it }, Modifier.fillMaxWidth(), singleLine = true, label = {
                    Label(strings["android.longitude"])
                })
                val latitude = lat.toDoubleOrNull()
                val longitude = lon.toDoubleOrNull()
                if (latitude != null && longitude != null) {
                    ActionButton(strings["onboarding.places.pinUse"]) { search(Coordinates(latitude, longitude)) }
                }
            }
            onClear?.let {
                ActionButton(strings["onboarding.destinations.none"], background = Color.White, onClick = it)
            }
        }
        DataSource(strings)
    }
    if (mapOpen) PlaceMapDialog(strings, mapCenter, { mapOpen = false }) {
        mapOpen = false
        search(it)
    }
}

@Composable
private fun DestinationScreen(
    strings: Language,
    session: GuideSession,
    site: EvacuationSite,
    back: () -> Unit,
    go: () -> Unit,
    end: () -> Unit,
) {
    val context = LocalContext.current
    var mapsFailed by rememberSaveable { mutableStateOf(false) }
    fun openMaps() {
        val url = Uri.parse("https://www.google.com/maps/dir/").buildUpon()
            .appendQueryParameter("api", "1")
            .appendQueryParameter("destination", site.coordinates.text)
            .appendQueryParameter("travelmode", "walking").build()
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, url))
        } catch (_: ActivityNotFoundException) {
            mapsFailed = true
        }
    }
    Screen(navTitle = strings[if (session.evacuating) "onTheWay.nav" else "destination.nav"],
        backLabel = strings["android.back"], onBack = back,
        actions = {
            if (!session.evacuating) ActionButton(strings["destination.go"], onClick = go)
            ActionButton(strings["android.openMaps"], background = Color.White, onClick = ::openMaps)
            if (session.evacuating) {
                ActionButton(strings[if (session.isTraining) "training.end" else "manual.end"],
                    background = Color.White, onClick = end)
            }
        },
    ) {
        if (session.isTraining) Badge(strings["training.badge"])
        if (session.evacuating) {
            val tsunami = session.phaseAt(System.currentTimeMillis()) == GuidePhase.Tsunami
            Title(strings[if (tsunami) "onTheWay.titleTsunami" else "onTheWay.title"])
        }
        Panel {
            Title(site.name, size = 22)
            Label(site.address)
            Label(site.coordinates.text, color = Muted)
        }
        PlaceMap(strings, site.coordinates)
        Label(strings["destination.note"], color = Muted)
        if (mapsFailed) {
            ActionButton(strings["destination.copyAddress"], background = Color.White) {
                context.getSystemService(ClipboardManager::class.java).setPrimaryClip(
                    ClipData.newPlainText(site.name, "${site.name}\n${site.address}\n${site.coordinates.text}"),
                )
            }
        }
        DataSource(strings)
    }
}

@Composable
private fun DataSource(strings: Language) {
    Label(strings["android.siteSource"], size = 12, color = Muted)
}

private fun distance(strings: Language, meters: Float): String {
    val format = NumberFormat.getNumberInstance(Locale.forLanguageTag(strings.id)).apply {
        maximumFractionDigits = 1
    }
    return if (meters < 1000) "${format.format(meters.toInt())} m" else "${format.format(meters / 1000)} km"
}
