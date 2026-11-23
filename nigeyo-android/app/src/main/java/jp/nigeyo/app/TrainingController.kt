package jp.nigeyo.app

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.WallpaperManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit
import org.json.JSONObject
import org.json.JSONArray
import java.io.IOException
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Executors

class NigeyoApplication : Application() {
    internal val training by lazy { TrainingController(this) }
}

internal data class GuideSession(
    val languageId: String,
    val scenario: Scenario,
    val alertAt: Long?,
    val checks: Boolean = false,
    val lastAlertAt: Long = 0,
    val changeWallpaper: Boolean = false,
    val destination: EvacuationSite? = null,
    val evacuating: Boolean = false,
    val id: String = UUID.randomUUID().toString(),
    val remoteMode: String? = null,
    val sourceEventId: String? = null,
    val sourceTime: Long = 0,
    val revision: Int = 1,
    val hazardRevision: Int = 0,
) {
    val isTraining get() = alertAt != null || remoteMode == "demo" || remoteMode == "remote-training"
    val tsunamiAt get() = alertAt?.plus(10_000)?.takeIf { scenario == Scenario.Combined }

    fun phaseAt(now: Long): GuidePhase? = when {
        alertAt != null && now < alertAt -> null
        scenario == Scenario.Tsunami || tsunamiAt?.let { now >= it } == true -> GuidePhase.Tsunami
        checks -> GuidePhase.Checks
        else -> GuidePhase.Protect
    }

    fun encode(): String = JSONObject().apply {
        put("language", languageId)
        put("scenario", scenario.name)
        put("alertAt", alertAt)
        put("checks", checks)
        put("lastAlertAt", lastAlertAt)
        put("changeWallpaper", changeWallpaper)
        put("destination", destination?.json())
        put("evacuating", evacuating)
        put("id", id)
        put("remoteMode", remoteMode)
        put("sourceEventId", sourceEventId)
        put("sourceTime", sourceTime)
        put("revision", revision)
        put("hazardRevision", hazardRevision)
    }.toString()

    companion object {
        fun restore(value: String?): GuideSession? = value?.let {
            runCatching {
                val json = JSONObject(it)
                GuideSession(
                    languageId = json.getString("language"),
                    scenario = Scenario.valueOf(json.getString("scenario")),
                    alertAt = json.optLong("alertAt").takeIf { time -> time > 0 },
                    checks = json.optBoolean("checks"),
                    lastAlertAt = json.optLong("lastAlertAt"),
                    changeWallpaper = json.optBoolean("changeWallpaper"),
                    destination = json.optJSONObject("destination")?.let(EvacuationSite::restore),
                    evacuating = json.optBoolean("evacuating"),
                    id = json.optString("id").ifBlank { UUID.randomUUID().toString() },
                    remoteMode = json.optString("remoteMode").ifBlank { null },
                    sourceEventId = json.optString("sourceEventId").ifBlank { null },
                    sourceTime = json.optLong("sourceTime"),
                    revision = json.optInt("revision", 1),
                    hazardRevision = json.optInt("hazardRevision"),
                )
            }.getOrNull()
        }
    }
}

/** Session transitions and notification delivery run on the main thread. */
internal class TrainingController(private val app: Application) {
    private val preferences = app.getSharedPreferences("nigeyo", Application.MODE_PRIVATE)
    private val notifications = app.getSystemService(NotificationManager::class.java)
    private val lockScreenGuide = LockScreenGuide(app)
    private var renderedGuide: Pair<String, GuidePhase>? = null
    private val handler = Handler(Looper.getMainLooper())
    private val checkSchedule = Runnable { refresh() }
    private val wallpaperWorker = Executors.newSingleThreadExecutor()
    @Volatile private var pendingWallpaperEvent: Long? = null

    private val secrets by lazy { SecretValues(app) }
    val content = GuideContent(app)
    val alerts by lazy { RegionalRegistration(app, secrets) }
    val demo by lazy { DemoConnection(app, this, secrets) }
    val sites = EvacuationSites(app)
    var savedSites by mutableStateOf(SiteSlot.all.mapNotNull { slot ->
        val site = preferences.getString(slot.key, null)?.let { value ->
            runCatching { EvacuationSite.restore(JSONObject(value)) }.getOrNull()
        }
        site?.takeIf(slot.hazard::supports)?.let { slot.key to it }
    }.toMap())
        private set
    var language by mutableStateOf(content.language(preferences.getString("language", null)))
        private set
    var session by mutableStateOf(GuideSession.restore(preferences.getString("session", null)))
        private set
    var phase by mutableStateOf(session?.phaseAt(System.currentTimeMillis()))
        private set
    var notificationsEnabled by mutableStateOf(canNotify())
        private set
    var storageError by mutableStateOf(false)
        private set
    var wallpaperUpdateFailed by mutableStateOf(false)
        private set
    var showLockScreenGuide by mutableStateOf(preferences.getBoolean("showLockScreenGuide", true))
        private set
    var onboardingDone by mutableStateOf(preferences.getBoolean("onboardingDone", false))
        private set
    var remoteWallpaper by mutableStateOf(preferences.getBoolean("remoteWallpaper", false))
        private set
    var diagnostics by mutableStateOf(stringList("diagnostics"))
        private set

    fun log(message: String) {
        diagnostics = (diagnostics + "${Instant.now()} $message").takeLast(100)
        preferences.edit { putString("diagnostics", JSONArray(diagnostics).toString()) }
    }

    fun changeRemoteWallpaperSetting(enabled: Boolean) {
        preferences.edit { putBoolean("remoteWallpaper", enabled) }
        remoteWallpaper = enabled
    }

    fun refreshRegionalMonitor() {
        val intent = Intent(app, RegionalMonitorService::class.java)
        if (!alerts.enabled || !alerts.cloudConfigured || !canNotify()) {
            app.stopService(intent)
            return
        }
        runCatching { app.startForegroundService(intent) }
    }

    fun reloadLanguages() {
        content.reload()
        language = content.language(language.id)
        demo.hello()
    }

    fun finishOnboarding() {
        preferences.edit { putBoolean("onboardingDone", true) }
        onboardingDone = true
    }

    suspend fun erasePreferences() {
        try {
            alerts.reset()
            refreshRegionalMonitor()
            demo.disconnect()
            secrets.clear()
        } catch (_: Exception) {
            storageError = true
            return
        }
        end()
        if (session != null) return
        if (!preferences.edit().clear().commit()) {
            storageError = true
            return
        }
        content.clearDownloaded()
        language = content.language(null)
        savedSites = emptyMap()
        onboardingDone = false
        showLockScreenGuide = true
        remoteWallpaper = false
        diagnostics = emptyList()
    }

    fun setLockScreenGuide(enabled: Boolean) {
        preferences.edit { putBoolean("showLockScreenGuide", enabled) }
        showLockScreenGuide = enabled
        refresh()
    }

    fun chooseLanguage(language: Language) {
        this.language = language
        preferences.edit { putString("language", language.id) }
        demo.hello()
    }

    fun start(scenario: Scenario? = null, changeWallpaper: Boolean = false) {
        if (session != null) return
        val alertAt = scenario?.let { System.currentTimeMillis() + 3_000 }
        val guide = GuideSession(
            language.id, scenario ?: Scenario.Earthquake, alertAt,
            changeWallpaper = scenario != null && changeWallpaper,
        )
        if (save(guide)) {
            log(if (scenario == null) "manual guide started" else "local drill started: ${scenario.name}")
            wallpaperUpdateFailed = false
            refresh()
        }
    }

    fun confirmShakingStopped(expectedId: String? = null) {
        val current = session ?: return
        if (expectedId != null && expectedId != current.id) return
        if (current.phaseAt(System.currentTimeMillis()) == GuidePhase.Protect) {
            if (save(current.copy(checks = true, revision = current.revision + 1))) log("shaking confirmed")
        }
        refresh()
    }

    fun saveSite(slot: SiteSlot, site: EvacuationSite?) {
        if (site != null && !slot.hazard.supports(site)) return
        val update = preferences.edit().putString(slot.key, site?.json()?.toString())
        if (!update.commit()) {
            storageError = true
            return
        }
        savedSites = if (site == null) savedSites - slot.key else savedSites + (slot.key to site)
    }

    fun chooseSite(site: EvacuationSite?) {
        val current = session ?: return
        val hazard = if (current.phaseAt(System.currentTimeMillis()) == GuidePhase.Tsunami) {
            Hazard.Tsunami
        } else {
            Hazard.Earthquake
        }
        if (site == null || hazard.supports(site)) save(current.copy(destination = site, evacuating = false))
    }

    fun confirmEvacuation() {
        val current = session ?: return
        if (current.destination != null && save(current.copy(evacuating = true))) refresh()
    }

    fun end() {
        val hadGuide = session != null
        if (!save(null)) return
        if (hadGuide) log("guidance ended")
        pendingWallpaperEvent = null
        notifications.cancel(NOTIFICATION_ID)
        refresh()
    }

    fun dismissStorageError() {
        storageError = false
    }

    fun refresh() {
        notificationsEnabled = canNotify()
        handler.removeCallbacks(checkSchedule)
        val current = session ?: return
        val now = System.currentTimeMillis()
        phase = current.phaseAt(now)
        updateLockScreenGuide(current)
        val times = listOfNotNull(current.alertAt, current.tsunamiAt)
        times.firstOrNull { it > now }?.let { handler.postDelayed(checkSchedule, it - now) }

        val due = times.lastOrNull { it <= now } ?: return
        if (due <= current.lastAlertAt) return
        val firstTsunami = due == current.tsunamiAt
        val updated = if (firstTsunami) current.copy(destination = null, evacuating = false) else current
        if (!save(updated.copy(lastAlertAt = due, revision = current.revision + 1))) return
        if (firstTsunami) updateLockScreenGuide(updated)

        // Resume the guide after a long absence without delivering stale drill effects.
        if (now - due >= 30_000) return

        val tsunami = current.scenario == Scenario.Tsunami || due == current.tsunamiAt
        if (notificationsEnabled) postAlert(content.language(current.languageId), tsunami)
        if (!current.changeWallpaper) return

        queueWallpaper(current.id, due, tsunami)
    }

    private fun queueWallpaper(sessionId: String, due: Long, tsunami: Boolean) {
        // Wallpaper completion can wait on the system renderer even after the image is set.
        pendingWallpaperEvent = due
        wallpaperWorker.execute {
            if (pendingWallpaperEvent == due) {
                val applied = updateLockWallpaper(tsunami)
                handler.post {
                    if (pendingWallpaperEvent == due && session?.id == sessionId) {
                        pendingWallpaperEvent = null
                        wallpaperUpdateFailed = !applied
                    }
                }
            }
        }
    }

    private fun seen(event: String): Boolean = receipts().any { it == event }

    private fun receipts(): List<String> = stringList("receipts")

    private fun stringList(key: String): List<String> = runCatching {
        val values = JSONArray(preferences.getString(key, "[]"))
        (0 until values.length()).map(values::getString)
    }.getOrDefault(emptyList())

    fun ingestInbox(data: Map<String, String>) {
        if (!alerts.enabled || !alerts.cloudConfigured) return
        when (data["nigeyoType"]) {
            "remote-training" -> {
                val id = data["eventId"]?.takeIf(::validId) ?: return
                val now = System.currentTimeMillis()
                val expires = instant(data["expiresAt"]) ?: return
                if (expires <= now || expires - now > 180_000 || seen(id)) return
                val scenario = when (data["hazard"]) {
                    "earthquake" -> Scenario.Earthquake
                    "tsunami" -> Scenario.Tsunami
                    else -> return
                }
                val current = session
                if (current?.remoteMode == "live" || (scenario == Scenario.Earthquake &&
                    current?.phaseAt(now) == GuidePhase.Tsunami)) return
                acceptRemote(GuideSession(language.id, scenario, null, id = id, remoteMode = "remote-training"), id)
            }
            "regional-alert" -> runCatching { JSONObject(data["regionalAlert"] ?: "") }.getOrNull()
                ?.let(::acceptRegional)
        }
    }

    fun ingestDemo(wire: JSONObject): JSONObject? {
        val type = wire.optString("type")
        if (type == "regional-alert") {
            val result = acceptRegional(wire)
            return JSONObject().put("schemaVersion", 2).put("type", "regional-ack")
                .put("commandId", wire.optString("commandId")).put("status", result)
        }
        val hazardUpdate = type == "hazard-update"
        if (!hazardUpdate && type != "start-guide") return null
        val idKey = if (hazardUpdate) "commandId" else "runId"
        val id = wire.optString(idKey)
        val now = System.currentTimeMillis()
        val ack = JSONObject().put("schemaVersion", 2).put("type", "notification-ack")
            .put(idKey, id).put("receivedAt", Instant.ofEpochMilli(now).toString())
            .put("appState", if (demo.foreground) "active" else "background")
            .put("protectedDataAvailable", true)
        if (hazardUpdate) ack.put("sessionId", wire.optString("sessionId"))
            .put("hazardRevision", wire.optInt("hazardRevision"))
        fun reject(reason: String): JSONObject = ack.put("status", "rejected").put("errorCode", reason)
        if (!validId(id) || wire.optString("mode") != "demo") return reject("invalid-payload")
        if (wire.optInt("schemaVersion") != 2) return reject("unsupported-version")
        val sent = instant(wire.optString("sentAt")) ?: return reject("invalid-payload")
        val expires = instant(wire.optString("expiresAt")) ?: return reject("invalid-payload")
        if (expires <= now) return reject("expired-command")
        if (sent - now >= 5_000) return reject("clock-skew")
        if (expires - sent !in 1..30_000) return reject("invalid-payload")
        if (seen(id)) return ack.put("status", "duplicate").put("stateRevision", session?.revision ?: 1)
        if (!canNotify()) return reject("notifications-denied")
        val current = session
        val strings = if (hazardUpdate && current != null) content.language(current.languageId) else language
        if (wire.optString("language") != strings.id) return reject("language-mismatch")
        if (wire.optInt("languagePackageVersion") != strings.version) return reject("language-package-missing")
        val next = if (hazardUpdate) {
            if (current?.remoteMode != "demo" || wire.optString("sessionId") != current.id ||
                wire.optString("hazard") != "tsunami" ||
                wire.optInt("hazardRevision") <= current.hazardRevision
            ) return reject("stale-revision")
            if (wire.optString("source") != "local" && wire.optJSONObject("tsunami")?.optBoolean("cancelled", true) != false) {
                return reject("invalid-payload")
            }
            current.copy(scenario = Scenario.Tsunami, checks = false, destination = null, evacuating = false,
                revision = current.revision + 1, hazardRevision = wire.getInt("hazardRevision"))
        } else {
            if (current != null) return reject("session-busy")
            val scenario = when (wire.optString("scenario")) {
                "earthquake", "earthquake-tsunami" -> Scenario.Earthquake
                "tsunami" -> Scenario.Tsunami
                else -> return reject("invalid-payload")
            }
            if (wire.optString("hazard") != if (scenario == Scenario.Tsunami) "tsunami" else "earthquake") {
                return reject("invalid-payload")
            }
            GuideSession(strings.id, scenario, null, id = id, remoteMode = "demo")
        }
        if (!acceptRemote(next, id)) return ack.put("status", "failed").put("errorCode", "storage-failed")
        return ack.put("status", "scheduled").put("scheduledAt", Instant.now().toString())
            .put("stateRevision", next.revision)
    }

    private fun acceptRemote(next: GuideSession, event: String): Boolean {
        if (!save(next, event)) return false
        log("${next.remoteMode} guide: ${next.scenario.name}")
        wallpaperUpdateFailed = false
        pendingWallpaperEvent = null
        refresh()
        val tsunami = next.scenario == Scenario.Tsunami
        if (canNotify() && next.isTraining) postAlert(content.language(next.languageId), tsunami)
        if (remoteWallpaper) queueWallpaper(next.id, System.currentTimeMillis(), tsunami)
        return true
    }

    private fun acceptRegional(wire: JSONObject): String {
        val commandId = wire.optString("commandId")
        val now = System.currentTimeMillis()
        val issued = instant(wire.optString("issuedAt")) ?: return "rejected"
        val expires = instant(wire.optString("expiresAt")) ?: return "rejected"
        if (wire.optInt("schemaVersion") != 2 || wire.optString("mode") != "live" ||
            !validId(commandId) || expires <= now || issued > now + 5_000) return "rejected"
        val hazard = Hazard.entries.firstOrNull { it.wireName == wire.optString("hazard") } ?: return "rejected"
        if (!alerts.matches(hazard, wire)) return "rejected"
        val status = wire.optString("status")
        val event = "$commandId:${wire.optString("sourceRevision")}:$issued"
        val source = wire.optString("sourceEventId").takeIf { it.isNotBlank() } ?: return "rejected"
        if (status !in setOf("active", "cancelled", "unknown")) return "rejected"
        if (seen(event)) return "stored"
        val previous = alerts.latest[hazard]
        val previousIssued = previous?.let { instant(it.optString("issuedAt")) }
        if (previousIssued != null && (issued < previousIssued ||
                (issued == previousIssued && previous.optString("status") == "cancelled"))) return "stored"
        val current = session
        val grade = wire.optString("grade")
        val notify = wire.optBoolean("notify") && status == "active"
        val action = hazard == Hazard.Earthquake || grade in setOf("Warning", "MajorWarning")
        if (notify && hazard == Hazard.Tsunami && !action && grade != "Watch") return "rejected"
        if (notify && action && (current?.remoteMode != "live" ||
                (issued >= current.sourceTime && (hazard == Hazard.Tsunami || current.scenario != Scenario.Tsunami)))
        ) {
            val next = GuideSession(language.id, if (hazard == Hazard.Tsunami) Scenario.Tsunami else Scenario.Earthquake,
                null, id = commandId, remoteMode = "live", sourceEventId = source, sourceTime = issued)
            if (!acceptRemote(next, event)) return "failed"
        } else {
            val sameTsunami = hazard == Hazard.Tsunami && current?.scenario == Scenario.Tsunami
            val zones = wire.optJSONObject("tsunamiAreas")
            val noWarningRemaining = zones != null && zones.length() > 0 &&
                status != "unknown" && zones.keys().asSequence()
                .all { zones.optString(it) in setOf("Watch", "Cancelled") }
            val cancelled = (status == "cancelled" && current?.sourceEventId == source) ||
                (sameTsunami && noWarningRemaining)
            val updated = if (current?.remoteMode == "live" && issued >= current.sourceTime && cancelled) null else current
            if (!save(updated, event)) return "failed"
            if (updated == null) {
                pendingWallpaperEvent = null
                notifications.cancel(NOTIFICATION_ID)
            }
        }
        alerts.record(hazard, wire)
        if (notify && canNotify()) postRegionalAlert(hazard, grade, wire)
        return "stored"
    }

    private fun validId(id: String): Boolean = runCatching { UUID.fromString(id).toString() == id.lowercase() }.getOrDefault(false)

    private fun updateLockWallpaper(tsunami: Boolean): Boolean {
        val image = if (tsunami) "wallpapers/tsunami.png" else "wallpapers/earthquake.png"
        return try {
            val manager = WallpaperManager.getInstance(app)
            if (!manager.isWallpaperSupported || !manager.isSetWallpaperAllowed) return false
            app.assets.open(image).use { stream ->
                manager.setStream(stream, null, false, WallpaperManager.FLAG_LOCK) > 0
            }
        } catch (error: IOException) {
            Log.w("Nigeyo", "Could not set drill wallpaper", error)
            false
        } catch (error: SecurityException) {
            Log.w("Nigeyo", "Wallpaper change blocked by Android", error)
            false
        }
    }

    private fun save(value: GuideSession?, receipt: String? = null): Boolean {
        val edit = preferences.edit()
        if (value == null) edit.remove("session") else edit.putString("session", value.encode())
        if (receipt != null) edit.putString("receipts", JSONArray((receipts() + receipt).takeLast(100)).toString())
        if (!edit.commit()) {
            storageError = true
            return false
        }
        session = value
        if (value == null) phase = null
        if (value == null) updateLockScreenGuide(null)
        return true
    }

    private fun updateLockScreenGuide(current: GuideSession?) {
        val currentPhase = phase
        if (current == null || currentPhase == null || current.evacuating ||
            !showLockScreenGuide || !notificationsEnabled
        ) {
            lockScreenGuide.clear()
            renderedGuide = null
            return
        }
        val key = current.id to currentPhase
        if (renderedGuide == key) return
        renderedGuide = key
        lockScreenGuide.render(current, currentPhase, content.language(current.languageId))
    }

    private fun canNotify(): Boolean =
        (Build.VERSION.SDK_INT < 33 ||
            app.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED) &&
            notifications.areNotificationsEnabled() &&
            notifications.getNotificationChannel(CHANNEL_ID)?.importance !=
            NotificationManager.IMPORTANCE_NONE

    private fun postAlert(strings: Language, tsunami: Boolean) {
        val channel = NotificationChannel(
            CHANNEL_ID, strings["android.notificationChannel"], NotificationManager.IMPORTANCE_HIGH,
        ).apply { lockscreenVisibility = Notification.VISIBILITY_PUBLIC }
        notifications.createNotificationChannel(channel)
        val launch = Intent(app, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val open = PendingIntent.getActivity(app, 0, launch, PendingIntent.FLAG_IMMUTABLE)
        val title = strings["training.badge"] + " · " + strings[
            if (tsunami) "guide.tsunami.short" else "guide.protect.short"
        ]
        val body = strings[
            if (tsunami) "notification.tsunami.bodyTraining" else "notification.earthquake.bodyTraining"
        ]
        val alert = Notification.Builder(app, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_drill_notification)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(Notification.BigTextStyle().bigText(body))
            .setContentIntent(open)
            .setAutoCancel(true)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .build()
        notifications.notify(NOTIFICATION_ID, alert)
    }

    private fun postRegionalAlert(hazard: Hazard, grade: String, wire: JSONObject) {
        val channel = NotificationChannel(
            REGIONAL_CHANNEL, language["regions.title"], NotificationManager.IMPORTANCE_HIGH,
        ).apply { lockscreenVisibility = Notification.VISIBILITY_PUBLIC }
        notifications.createNotificationChannel(channel)
        val key = if (hazard == Hazard.Earthquake) "earthquake" else grade
        val matches = wire.getJSONArray("matches")
        val regions = (0 until matches.length()).mapNotNull { matches.optJSONObject(it)?.optString("region") }
            .distinct().joinToString(" / ")
        val body = language["regions.alert.$key"] + "\n" + regions
        val open = PendingIntent.getActivity(app, 5, Intent(app, MainActivity::class.java)
            .setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE)
        notifications.notify(NOTIFICATION_ID, Notification.Builder(app, REGIONAL_CHANNEL)
            .setSmallIcon(R.drawable.ic_drill_notification)
            .setContentTitle(language["hazard.${hazard.wireName}"])
            .setContentText(body)
            .setStyle(Notification.BigTextStyle().bigText(body))
            .setContentIntent(open)
            .setAutoCancel(true)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .build())
    }

    private companion object {
        const val CHANNEL_ID = "drills"
        const val REGIONAL_CHANNEL = "regional-alerts"
        const val NOTIFICATION_ID = 1
    }
}
