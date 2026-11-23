package jp.nigeyo.app

import android.app.Application
import android.content.Context
import android.util.JsonReader
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.zip.Inflater
import java.util.zip.InflaterInputStream
import kotlin.math.max
import kotlin.math.min

internal data class NotificationArea(val id: String, val name: String, val pref: String = "") {
    val label get() = if (pref.isNotEmpty()) "$pref · $name" else name
}
internal data class RegisteredAreas(val quake: String, val tsunami: String?)
internal data class RegionSuggestions(val earthquake: Set<String>, val tsunami: Set<String>)

internal class RegionalRegistration(
    private val app: Application,
    private val secrets: SecretValues,
) {
    private val settings = app.getSharedPreferences("nigeyo", Context.MODE_PRIVATE)
    private val lock = Mutex()
    private val table by lazy {
        app.assets.open("notification-regions-national.json").bufferedReader().use { JSONObject(it.readText()) }
    }
    val earthquakes by lazy { areas("earthquakeAreas") }
    val tsunamis by lazy { areas("tsunamiAreas") }
    var regions by mutableStateOf(Place.entries.mapNotNull { place ->
        settings.getString("region.${place.wireName}", null)?.let { value ->
            runCatching {
                val region = JSONObject(value)
                place to RegisteredAreas(region.getString("quake"), region.optString("tsunami").ifBlank { null })
            }.getOrNull()
        }
    }.toMap())
        private set
    var enabled by mutableStateOf(settings.getBoolean("regionalEnabled", false))
        private set
    var synced by mutableStateOf(false)
        private set
    var latest by mutableStateOf(Hazard.entries.mapNotNull { hazard ->
        settings.getString("latest.${hazard.wireName}", null)?.let { value ->
            runCatching { hazard to JSONObject(value) }.getOrNull()
        }
    }.toMap())
        private set
    private var lastBody: String? = null
    val cloudConfigured get() = BuildConfig.REGISTRATION_HOST.isNotBlank()
    var needsRevocation by mutableStateOf(secrets.read("liveBootstrap") != null)
        private set
    var installationId by mutableStateOf(secrets.read("liveCredential")?.substringBefore('.'))
        private set

    private fun areas(key: String): List<NotificationArea> {
        val list = table.getJSONArray(key)
        return (0 until list.length()).map {
            val item = list.getJSONObject(it)
            NotificationArea(item.getString("id"), item.getString("name"), item.optString("pref"))
        }
    }

    fun setAreas(place: Place, area: RegisteredAreas?) {
        if (enabled) return
        val value = area?.let { JSONObject().put("quake", it.quake).put("tsunami", it.tsunami).toString() }
        settings.edit {
            putString("region.${place.wireName}", value)
            Hazard.entries.forEach { remove("latest.${it.wireName}") }
        }
        latest = emptyMap()
        regions = if (area == null) regions - place else regions + (place to area)
    }

    private fun targets(): JSONArray = JSONArray().also { output ->
        regions.forEach { (place, region) ->
            val quake = earthquakes.firstOrNull { it.id == region.quake } ?: return@forEach
            val tsunami = region.tsunami?.let { id -> tsunamis.firstOrNull { it.id == id } }
            output.put(JSONObject().put("slot", "${place.wireName}.earthquake")
                .put("siteId", "place:${place.wireName}")
                .put("earthquakeArea", JSONObject().put("pref", quake.pref).put("name", quake.name))
                .put("earthquakeAreaId", quake.id)
                .put("tsunamiStatus", if (tsunami == null) "none" else "configured")
                .put("tsunamiAreas", JSONArray(listOfNotNull(tsunami?.name)))
                .put("tsunamiAreaIds", JSONArray(listOfNotNull(tsunami?.id))))
        }
    }

    fun demoSubscription(): JSONObject? = if (!cloudConfigured && enabled) subscription() else null

    private fun subscription(): JSONObject = JSONObject().put("version", 2).put("enabled", enabled).put("targets", targets())

    fun record(hazard: Hazard, message: JSONObject) {
        settings.edit { putString("latest.${hazard.wireName}", message.toString()) }
        latest = latest + (hazard to message)
    }

    suspend fun suggest(coordinates: Coordinates): RegionSuggestions = withContext(Dispatchers.IO) {
        val x = coordinates.longitude * 100_000
        val y = coordinates.latitude * 100_000
        fun boundaries(kind: String): Set<String> = buildSet {
            val inflater = Inflater(true)
            try {
                val source = app.assets.open("notification-region-$kind-boundaries.deflate")
                JsonReader(InflaterInputStream(source, inflater, 32768).bufferedReader()).use { reader ->
                    reader.beginArray()
                    while (reader.hasNext()) {
                        ensureActive()
                        reader.beginArray()
                        val id = reader.nextString()
                        reader.beginArray()
                        val west = reader.nextInt()
                        val south = reader.nextInt()
                        val east = reader.nextInt()
                        val north = reader.nextInt()
                        reader.endArray()
                        if (x in (west - 35.0)..(east + 35.0) && y in (south - 35.0)..(north + 35.0)) {
                            if (reader.contains(x, y)) add(id)
                        } else reader.skipValue()
                        reader.endArray()
                    }
                    reader.endArray()
                }
            } finally {
                inflater.end()
            }
        }
        val municipality = boundaries("municipality")
        val mapping = table.getJSONObject("municipalTsunamiAreas")
        val tsunami = municipality.flatMap { id ->
            val values = mapping.optJSONArray(id) ?: return@flatMap emptyList()
            (0 until values.length()).map(values::getString)
        }.toSet()
        RegionSuggestions(boundaries("earthquake"), tsunami)
    }

    fun matches(hazard: Hazard, message: JSONObject): Boolean {
        if (!enabled) return false
        val matches = message.optJSONArray("matches") ?: return false
        for (i in 0 until matches.length()) {
            val match = matches.optJSONObject(i) ?: continue
            val place = regions.keys.firstOrNull { match.optString("slot") == "${it.wireName}.earthquake" } ?: continue
            if (match.optString("siteId") != "place:${place.wireName}") continue
            val configured = regions.getValue(place)
            val area = if (hazard == Hazard.Earthquake) {
                earthquakes.firstOrNull { it.id == configured.quake }
            } else {
                tsunamis.firstOrNull { it.id == configured.tsunami }
            }
            if (area?.name == match.optString("region")) return true
        }
        return false
    }

    suspend fun setEnabled(value: Boolean, language: String) {
        require(!value || targets().length() > 0)
        enabled = value
        settings.edit { putBoolean("regionalEnabled", value) }
        synchronize(language)
    }

    suspend fun synchronize(language: String) {
        if (!cloudConfigured) return
        lock.withLock {
            try {
                if (!enabled) {
                    withContext(Dispatchers.IO) { revoke() }
                    synced = false
                    needsRevocation = false
                    installationId = null
                    return
                }
                val request = subscription().put("platform", "android")
                    .put("language", language.substringBefore('-').takeIf { it in setOf("en", "ja", "ko") } ?: "en")
                    .toString()
                if (lastBody == request) return
                withContext(Dispatchers.IO) {
                    val credential = credential()
                    val response = send("subscription", "PUT", credential, request).first
                    if (response == 403) {
                        check(send("subscription", "PUT", enroll(), request).first == 204)
                    } else {
                        check(response == 204)
                    }
                }
                lastBody = request
                synced = true
                needsRevocation = true
                installationId = secrets.read("liveCredential")?.substringBefore('.')
            } catch (error: Exception) {
                lastBody = null
                synced = false
                needsRevocation = secrets.read("liveBootstrap") != null
                throw error
            }
        }
    }

    suspend fun reset() {
        setEnabled(false, "en")
        regions = emptyMap()
        latest = emptyMap()
    }

    private fun revoke() {
        if (secrets.read("liveBootstrap") == null) return
        val response = send("installations", "DELETE", credential()).first
        if (response == 403) {
            check(send("installations", "DELETE", enroll()).first == 204)
        } else check(response == 204)
        secrets.write("liveCredential", null)
        secrets.write("liveBootstrap", null)
        lastBody = null
    }

    private fun credential(): String = secrets.read("liveCredential") ?: enroll()

    private fun enroll(): String {
        val boot = secrets.read("liveBootstrap") ?: randomHex().also { secrets.write("liveBootstrap", it) }
        val (status, text) = send("installations", "PUT", boot)
        check(status == 200)
        val received = JSONObject(text).getString("credential")
        require(received.matches(Regex("[a-f0-9]{64}\\.[a-f0-9]{64}")))
        secrets.write("liveCredential", received)
        return received
    }

    private fun send(path: String, method: String, bearer: String, body: String? = null): Pair<Int, String> {
        val data = if (method == "PUT") (body ?: "").toRequestBody("application/json".toMediaType()) else null
        val request = Request.Builder().url("https://${BuildConfig.REGISTRATION_HOST}/v2/$path")
            .header("Authorization", "Bearer $bearer").method(method, data).build()
        return networkClient.newCall(request).execute().use {
            val source = it.body?.source()
            val text = if (source != null && !source.request(32_769)) source.readUtf8() else ""
            it.code to text
        }
    }

    suspend fun poll(): List<JSONObject> = withContext(Dispatchers.IO) {
        val credential = secrets.read("liveCredential") ?: return@withContext emptyList()
        val (status, text) = send("alerts", "GET", credential)
        if (status == 403) {
            throw RegistrationExpired()
        }
        check(status == 200)
        val list = JSONObject(text).getJSONArray("alerts")
        require(list.length() <= 3)
        (0 until list.length()).map(list::getJSONObject)
    }

    class RegistrationExpired : IllegalStateException()

    fun markDisconnected(resetRegistration: Boolean) {
        synced = false
        if (resetRegistration) lastBody = null
    }

    fun markConnected() { synced = true }
}

private fun JsonReader.contains(x: Double, y: Double): Boolean {
    var inside = false
    var near = false
    beginArray()
    while (hasNext()) {
        beginArray()
        if (hasNext()) {
            var ax = nextDouble()
            var ay = nextDouble()
            while (hasNext()) {
                val bx = nextDouble()
                val by = nextDouble()
                if (x in (min(ax, bx) - 35)..(max(ax, bx) + 35) &&
                    y in (min(ay, by) - 35)..(max(ay, by) + 35)
                ) {
                    val dx = bx - ax
                    val dy = by - ay
                    val length = dx * dx + dy * dy
                    val f = if (length == 0.0) 0.0 else (((x - ax) * dx + (y - ay) * dy) / length).coerceIn(0.0, 1.0)
                    val rx = x - (ax + f * dx)
                    val ry = y - (ay + f * dy)
                    if (rx * rx + ry * ry <= 35 * 35) near = true
                }
                if ((ay > y) != (by > y) && x < ax + (y - ay) * (bx - ax) / (by - ay)) inside = !inside
                ax = bx
                ay = by
            }
        }
        endArray()
    }
    endArray()
    return inside || near
}
