package jp.nigeyo.app

import android.content.Context
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.os.CancellationSignal
import android.util.JsonReader
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.util.Locale
import java.util.PriorityQueue
import kotlin.coroutines.resume
import kotlin.math.cos

internal enum class Hazard(val wireName: String) {
    Earthquake("earthquake"), Tsunami("tsunami");

    fun supports(site: EvacuationSite): Boolean = when (this) {
        Earthquake -> site.earthquake
        Tsunami -> site.tsunami
    }
}

internal enum class Place(val wireName: String) {
    Home("home"), School("university");

    fun title(strings: Language): String = strings["onboarding.places.$wireName"]
}

internal data class SiteSlot(val place: Place, val hazard: Hazard) {
    val key get() = "${place.wireName}.${hazard.wireName}"
    fun label(strings: Language) = strings.format(
        "onboarding.destinations.slot", "place" to place.title(strings),
        "hazard" to strings["hazard.${hazard.wireName}"],
    )

    companion object {
        val all = Place.entries.flatMap { place -> Hazard.entries.map { SiteSlot(place, it) } }
    }
}

internal data class Coordinates(val latitude: Double, val longitude: Double) {
    val isValid get() = latitude in -90.0..90.0 && longitude in -180.0..180.0
    val text get() = "$latitude,$longitude"
}

internal data class EvacuationSite(
    val id: String,
    val name: String,
    val address: String,
    val coordinates: Coordinates,
    val earthquake: Boolean,
    val tsunami: Boolean,
) {
    fun json(): JSONObject = JSONObject()
        .put("id", id).put("name", name).put("address", address)
        .put("latitude", coordinates.latitude).put("longitude", coordinates.longitude)
        .put("earthquake", earthquake).put("tsunami", tsunami)

    companion object {
        fun restore(json: JSONObject): EvacuationSite = EvacuationSite(
            json.getString("id"), json.getString("name"), json.getString("address"),
            Coordinates(json.getDouble("latitude"), json.getDouble("longitude")),
            json.getBoolean("earthquake"), json.getBoolean("tsunami"),
        )
    }
}

internal data class SiteDistance(val site: EvacuationSite, val meters: Float)

internal class EvacuationSites(private val context: Context) {
    private val sites by lazy {
        context.assets.open("evacuation-sites.json").bufferedReader().use { text ->
            JsonReader(text).use { reader ->
                buildMap {
                    reader.beginArray()
                    while (reader.hasNext()) {
                        val site = reader.site()
                        put(site.id, site)
                    }
                    reader.endArray()
                }
            }
        }
    }

    suspend fun nearest(origin: Coordinates, hazard: Hazard, limit: Int): List<SiteDistance> =
        withContext(Dispatchers.IO) {
            val order = compareBy<SiteDistance>({ it.meters }, { it.site.id })
            val nearest = PriorityQueue(limit + 1, order.reversed())
            val result = FloatArray(1)
            val longitudeWindow = 1 / cos(Math.toRadians(origin.latitude)).coerceAtLeast(0.01)
            for (site in sites.values) {
                ensureActive()
                val (latitude, longitude) = site.coordinates
                if (!hazard.supports(site) || kotlin.math.abs(latitude - origin.latitude) > 1 ||
                    kotlin.math.abs(longitude - origin.longitude) > longitudeWindow
                ) continue
                Location.distanceBetween(origin.latitude, origin.longitude, latitude, longitude, result)
                if (result[0] <= 100_000f) {
                    nearest.add(SiteDistance(site, result[0]))
                    if (nearest.size > limit) nearest.poll()
                }
            }
            nearest.sortedWith(order)
        }

    private fun JsonReader.site(): EvacuationSite {
        var id = ""
        var name = ""
        var address = ""
        var lat = Double.NaN
        var lon = Double.NaN
        var earthquake = false
        var tsunami = false
        beginObject()
        while (hasNext()) {
            when (nextName()) {
                "id" -> id = nextString()
                "name" -> name = nextString()
                "address" -> address = nextString()
                "latitude" -> lat = nextDouble()
                "longitude" -> lon = nextDouble()
                "supportsEarthquake" -> earthquake = nextBoolean()
                "supportsTsunami" -> tsunami = nextBoolean()
                else -> skipValue()
            }
        }
        endObject()
        val coordinates = Coordinates(lat, lon)
        require(id.isNotBlank() && name.isNotBlank() && coordinates.isValid)
        return EvacuationSite(id, name, address, coordinates, earthquake, tsunami)
    }
}

internal suspend fun locateOnce(context: Context): Location? = withTimeoutOrNull(8_000) {
    val manager = context.getSystemService(LocationManager::class.java)
    val providers = listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER)
        .filter(manager::isProviderEnabled)
    if (providers.isEmpty()) return@withTimeoutOrNull null
    suspendCancellableCoroutine { continuation ->
        val cancellations = List(providers.size) { CancellationSignal() }
        continuation.invokeOnCancellation { cancellations.forEach(CancellationSignal::cancel) }
        var remaining = providers.size
        var delivered = false
        fun receive(fix: Location?) {
            if (delivered || !continuation.isActive) return
            remaining--
            if (fix != null || remaining == 0) {
                delivered = true
                cancellations.forEach(CancellationSignal::cancel)
                continuation.resume(fix)
            }
        }
        providers.zip(cancellations).forEach { (provider, cancellation) ->
            try {
                LocationManagerCompat.getCurrentLocation(
                    manager, provider, cancellation, ContextCompat.getMainExecutor(context), ::receive,
                )
            } catch (_: SecurityException) {
                receive(null)
            } catch (_: IllegalArgumentException) {
                receive(null)
            }
        }
    }
}

@Suppress("DEPRECATION")
internal suspend fun geocodePlace(context: Context, query: String, languageId: String) =
    withContext(Dispatchers.IO) {
        if (!Geocoder.isPresent()) return@withContext emptyList()
        Geocoder(context, Locale.forLanguageTag(languageId)).getFromLocationName(
            query, 5, 20.0, 120.0, 46.5, 154.0,
        ).orEmpty().filter { it.hasLatitude() && it.hasLongitude() }.map {
            (it.getAddressLine(0) ?: it.featureName ?: query) to Coordinates(it.latitude, it.longitude)
        }
    }
