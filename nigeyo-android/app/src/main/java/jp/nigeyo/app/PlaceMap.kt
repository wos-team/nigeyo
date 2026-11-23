package jp.nigeyo.app

import android.graphics.BitmapFactory
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import okhttp3.Request
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.math.sinh
import kotlin.math.tan

private data class Mercator(val x: Double, val y: Double) {
    fun coordinate(): Coordinates = Coordinates(
        Math.toDegrees(atan(sinh(PI * (1 - 2 * y.coerceIn(0.0, 1.0))))),
        x.coerceIn(0.0, 1.0) * 360 - 180,
    )
}

private fun Coordinates.mercator() = Mercator(
    (longitude + 180) / 360,
    (1 - ln(tan(PI / 4 + Math.toRadians(latitude.coerceIn(-85.0, 85.0)) / 2)) / PI) / 2,
)
private data class Tile(val x: Int, val y: Int, val zoom: Int)
private data class MapTile(val tile: Tile, val image: ImageBitmap)
private val defaultCenter = Coordinates(35.6812, 139.7671)

@Composable
internal fun PlaceMapDialog(
    strings: Language,
    initial: Coordinates?,
    close: () -> Unit,
    choose: (Coordinates) -> Unit,
) {
    var center by remember { mutableStateOf(initial ?: defaultCenter) }
    var pin by remember { mutableStateOf(initial) }
    var zoom by remember { mutableIntStateOf(if (initial == null) 10 else 15) }
    AlertDialog(onDismissRequest = close, title = { Label(strings["onboarding.places.pinTitle"], bold = true) },
        text = {
            Column {
                PlaceMap(strings, center, zoom, pin = pin, pan = { center = it }, select = { pin = it })
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton(onClick = { zoom = (zoom - 1).coerceAtLeast(5) }) { Label("−", size = 18) }
                    pin?.let { Label("%.5f, %.5f".format(java.util.Locale.ROOT, it.latitude, it.longitude), size = 11) }
                    TextButton(onClick = { zoom = (zoom + 1).coerceAtMost(18) }) { Label("+", size = 18) }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = pin != null, onClick = { pin?.let(choose) }) {
                Label(strings["onboarding.places.pinUse"])
            }
        },
        dismissButton = { TextButton(onClick = close) { Label(strings["common.cancel"]) } },
    )
}

@Composable
internal fun PlaceMap(
    strings: Language,
    center: Coordinates,
    zoom: Int = 15,
    pin: Coordinates? = center,
    pan: ((Coordinates) -> Unit)? = null,
    select: ((Coordinates) -> Unit)? = null,
) {
    val uri = LocalUriHandler.current
    val world = 1 shl zoom
    val point = center.mercator()
    val gridX = floor(point.x * world).toInt()
    val gridY = floor(point.y * world).toInt()
    var images by remember { mutableStateOf(emptyList<MapTile>()) }
    var loaded by remember { mutableStateOf(false) }
    var drag by remember { mutableStateOf(Offset.Zero) }
    LaunchedEffect(gridX, gridY, zoom) {
        loaded = false
        val tiles = (-1..1).flatMap { dx ->
            (-1..1).map { dy -> Tile(gridX + dx, gridY + dy, zoom) }
        }.filter { it.x in 0 until world && it.y in 0 until world }
        images = coroutineScope {
            tiles.map { tile ->
                async(Dispatchers.IO) { loadTile(tile) }
            }.awaitAll().filterNotNull()
        }
        loaded = true
    }
    val label = strings["destination.mapCaption"]
    var modifier = Modifier.fillMaxWidth().aspectRatio(1.3f).background(Paper)
        .semantics { contentDescription = label }
    if (pan != null) modifier = modifier.pointerInput(point, zoom) {
        detectDragGestures(
            onDragEnd = {
                pan(Mercator(point.x - drag.x / size.width / world, point.y - drag.y / size.width / world).coordinate())
                drag = Offset.Zero
            },
            onDragCancel = { drag = Offset.Zero },
        ) { change, amount ->
            change.consume()
            drag += amount
        }
    }
    if (select != null) modifier = modifier.pointerInput(point, zoom, loaded) {
        detectTapGestures { position ->
            if (loaded && images.isNotEmpty()) {
                select(Mercator(point.x + (position.x - size.width / 2) / size.width / world,
                    point.y + (position.y - size.height / 2) / size.width / world).coordinate())
            }
        }
    }
    if (!loaded || images.isNotEmpty()) {
        Canvas(modifier) {
            val tileSize = size.width
            images.filter { it.tile.zoom == zoom }.forEach { (tile, image) ->
                val x = ((tile.x - point.x * world) * tileSize + this.center.x + drag.x).roundToInt()
                val y = ((tile.y - point.y * world) * tileSize + this.center.y + drag.y).roundToInt()
                drawImage(image, dstOffset = IntOffset(x, y),
                    dstSize = IntSize(tileSize.roundToInt() + 1, tileSize.roundToInt() + 1))
            }
            pin?.mercator()?.let {
                val where = Offset(
                    ((it.x - point.x) * world * tileSize).toFloat() + this.center.x + drag.x,
                    ((it.y - point.y) * world * tileSize).toFloat() + this.center.y + drag.y,
                )
                drawCircle(Ink, 11f, where)
                drawCircle(Orange, 7f, where)
            }
        }
        TextButton(onClick = { uri.openUri("https://maps.gsi.go.jp/development/ichiran.html") }) {
            Label(strings["android.mapSource"], size = 11, color = Muted)
        }
    } else Label(strings["android.mapUnavailable"], color = Muted)
}


private fun loadTile(tile: Tile): MapTile? {
    val (x, y, zoom) = tile
    return try {
        val url = "https://cyberjapandata.gsi.go.jp/xyz/std/$zoom/$x/$y.png"
        networkClient.newCall(Request.Builder().url(url).build()).execute().use { response ->
            if (!response.isSuccessful) return null
            val body = response.body?.source() ?: return null
            if (body.request(512 * 1024 + 1L)) return null
            val bytes = body.readByteArray()
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.let { MapTile(tile, it.asImageBitmap()) }
        }
    } catch (error: java.io.IOException) {
        null
    }
}
