package jp.nigeyo.app

import android.content.Context
import android.util.AtomicFile
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

internal class Language(
    val id: String,
    val nativeName: String,
    val version: Int,
    val isRtl: Boolean,
    private val strings: JSONObject,
    private val androidStrings: JSONObject,
    private val englishAndroidStrings: JSONObject,
) {
    operator fun get(key: String): String = when {
        !key.startsWith("android.") -> strings.getString(key)
        androidStrings.has(key) -> androidStrings.getString(key)
        else -> englishAndroidStrings.getString(key)
    }

    fun format(key: String, vararg values: Pair<String, String>): String =
        values.fold(this[key]) { text, (name, value) -> text.replace("{$name}", value) }
}

internal data class ActionCard(
    val pictogram: String,
    val titleKey: String,
    val bodyKey: String,
    val accessibilityKey: String,
)

internal class GuideContent(context: Context) {
    private val assets = context.assets
    private val cache = File(context.filesDir, "languages")
    private fun json(path: String): JSONObject =
        assets.open(path).bufferedReader().use { JSONObject(it.readText()) }
    private val platformStrings = json("android-strings.json")
    private val contract by lazy { json("required-string-keys.json").getJSONObject("keys") }
    private val languageId = Regex("[a-z]{2,8}(?:-[a-zA-Z0-9]{1,8})*")
    private val variable = Regex("\\{([a-zA-Z0-9_]+)\\}")

    private val bundled = assets.list("languages").orEmpty().sorted().map { parse(json("languages/$it")) }
    var languages by mutableStateOf(load())
        private set
    private val phases: Map<String, List<ActionCard>>

    init {
        val cards = json("cards.json")
        val actions = cards.getJSONArray("actions")
        val actionsById = (0 until actions.length()).associate { index ->
            val action = actions.getJSONObject(index)
            action.getString("id") to ActionCard(
                pictogram = action.getString("pictogram"),
                titleKey = action.getString("titleKey"),
                bodyKey = action.getString("bodyKey"),
                accessibilityKey = action.getString("a11yKey"),
            )
        }
        val definitions = cards.getJSONObject("phases")
        phases = definitions.keys().asSequence().associateWith { phase ->
            val ids = definitions.getJSONArray(phase)
            (0 until ids.length()).map { actionsById.getValue(ids.getString(it)) }
        }
    }

    fun language(id: String?): Language =
        languages.firstOrNull { it.id == id } ?: languages.first { it.id == "en" }

    fun reload() { languages = load() }

    fun clearDownloaded() {
        cache.deleteRecursively()
        languages = bundled
    }

    private fun parse(pack: JSONObject): Language {
        val id = pack.getString("languageId")
        return Language(id, pack.getString("nativeName"), pack.getInt("packageVersion"),
            pack.getString("direction") == "rtl", pack.getJSONObject("strings"),
            platformStrings.optJSONObject(id) ?: JSONObject(), platformStrings.getJSONObject("en"))
    }

    private fun load(): List<Language> {
        val extras = cache.listFiles().orEmpty().filter { it.extension == "json" }.mapNotNull {
            runCatching { validate(JSONObject(AtomicFile(it).readFully().decodeToString())) }.getOrNull()
        }
        return (bundled + extras).groupBy { it.id }.values
            .map { values -> values.maxBy { it.version } }.sortedBy { it.id }
    }

    private fun validate(pack: JSONObject): Language {
        val language = parse(pack)
        require(pack.getInt("schemaVersion") == 1 && language.version > 0 &&
            language.id.matches(languageId) && language.nativeName.isNotBlank() &&
            pack.getString("direction") in setOf("ltr", "rtl"))
        contract.keys().forEach { key ->
            val text = language[key]
            require(text.isNotBlank())
            require(variable.findAll(text).map { it.groupValues[1] }.toSet() ==
                contract.getJSONObject(key).keys().asSequence().toSet())
        }
        return language
    }

    suspend fun download(origin: HttpUrl): Pair<Int, Int> = withContext(Dispatchers.IO) {
        fun fetch(path: String): ByteArray {
            val url = requireNotNull(origin.resolve("/public/languages/$path"))
            return networkClient.newCall(Request.Builder().url(url).build()).execute().use { response ->
                check(response.isSuccessful)
                val limit = 256 * 1024L
                val stream = requireNotNull(response.body).source()
                check(!stream.request(limit + 1))
                stream.readByteArray()
            }
        }
        val manifest = JSONObject(fetch("manifest.json").decodeToString())
        require(manifest.getInt("schemaVersion") == 1)
        val entries = manifest.getJSONArray("languages")
        val existing = load().associateBy { it.id }
        val staged = mutableMapOf<String, Pair<Language, ByteArray>>()
        require(entries.length() <= 100)
        repeat(entries.length()) { index ->
            val entry = entries.getJSONObject(index)
            val id = entry.getString("languageId")
            val version = entry.getInt("packageVersion")
            val file = entry.getString("file")
            require(id.matches(languageId) && file == "$id.json" && version > 0)
            if ((existing[id]?.version ?: 0) >= version) return@repeat
            val bytes = fetch(file)
            val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            require(hash == entry.getString("sha256") && bytes.size == entry.getInt("bytes"))
            val pack = validate(JSONObject(bytes.decodeToString()))
            require(pack.id == id && pack.version == version && id !in staged)
            staged[id] = pack to bytes
        }
        cache.mkdirs()
        staged.forEach { (id, packageData) ->
            val file = AtomicFile(File(cache, "$id.json"))
            val stream = file.startWrite()
            try {
                stream.write(packageData.second)
                file.finishWrite(stream)
            } catch (error: Exception) {
                file.failWrite(stream)
                throw error
            }
        }
        val added = staged.keys.count { it !in existing }
        added to staged.size - added
    }

    fun cards(phase: GuidePhase): List<ActionCard> = phases.getValue(phase.cardPhase)
}

internal enum class Scenario(val titleKey: String, val bodyKey: String, val startKey: String) {
    Earthquake("practice.earthquake", "practice.earthquakeBody", "practice.start"),
    Combined("practice.tsunami", "practice.tsunamiBody", "practice.startTsunami"),
    Tsunami("practice.tsunamiOnly", "practice.tsunamiOnlyBody", "practice.startTsunamiOnly"),
}

internal enum class GuidePhase(val cardPhase: String) {
    Protect("protect"),
    Checks("safety-checks"),
    Tsunami("tsunami-evacuate"),
}
