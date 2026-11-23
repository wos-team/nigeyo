package jp.nigeyo.app

import android.app.Application
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Base64
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.security.KeyStore
import java.security.SecureRandom
import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties

internal val networkClient = OkHttpClient.Builder()
    .connectTimeout(8, TimeUnit.SECONDS)
    .readTimeout(10, TimeUnit.SECONDS)
    .writeTimeout(10, TimeUnit.SECONDS)
    .callTimeout(15, TimeUnit.SECONDS)
    .followRedirects(false)
    .followSslRedirects(false)
    .build()

internal class SecretValues(context: Context) {
    private val prefs = context.getSharedPreferences("nigeyo-secrets", Context.MODE_PRIVATE)
    private val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    private fun key(): SecretKey {
        (keyStore.getKey("nigeyo-storage", null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder("nigeyo-storage",
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build())
            generateKey()
        }
    }

    @Synchronized
    fun read(name: String): String? {
        val encoded = prefs.getString(name, null) ?: return null
        return runCatching {
            val data = Base64.decode(encoded, Base64.NO_WRAP)
            require(data.size > 12)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, data.copyOfRange(0, 12)))
            cipher.updateAAD(name.toByteArray())
            cipher.doFinal(data, 12, data.size - 12).decodeToString()
        }.getOrNull()
    }

    @Synchronized
    fun write(name: String, value: String?) {
        if (value == null) {
            check(prefs.edit().remove(name).commit())
            return
        }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        cipher.updateAAD(name.toByteArray())
        val encoded = cipher.iv + cipher.doFinal(value.toByteArray())
        check(prefs.edit().putString(name, Base64.encodeToString(encoded, Base64.NO_WRAP)).commit())
    }

    @Synchronized
    fun clear() {
        check(prefs.edit().clear().commit())
        keyStore.deleteEntry("nigeyo-storage")
    }
}

internal class DemoConnection(
    app: Application,
    private val training: TrainingController,
    private val storage: SecretValues,
) {
    private data class Pairing(val endpoint: HttpUrl, val token: String) {
        companion object {
            fun parse(value: String): Pairing? {
                if (!value.startsWith("wss://")) return null
                val url = ("https://" + value.removePrefix("wss://")).toHttpUrlOrNull() ?: return null
                if (url.encodedPath != "/public/device" || url.username.isNotEmpty() ||
                    url.password.isNotEmpty() || url.fragment != null || url.querySize != 1
                ) return null
                val token = url.queryParameter("token") ?: return null
                if (!token.matches(Regex("[A-Za-z0-9_-]{20,128}"))) return null
                return Pairing(url.newBuilder().query(null).build(), token)
            }
        }
    }

    private val preferences = app.getSharedPreferences("nigeyo", Context.MODE_PRIVATE)
    private val handler = Handler(Looper.getMainLooper())
    private val client = networkClient.newBuilder().readTimeout(0, TimeUnit.MILLISECONDS).build()
    private var pairing: Pairing? = null
    private var socket: WebSocket? = null
    private var retry = 0
    private val connectLater = Runnable { open() }
    private val heartbeat = object : Runnable {
        override fun run() {
            val connection = socket ?: return
            connection.send(JSONObject()
                .put("schemaVersion", 2).put("type", "heartbeat")
                .put("installationId", installationId())
                .put("appState", if (foreground) "active" else "background")
                .put("protectedDataAvailable", true)
                .put("currentSessionId", training.session?.id ?: JSONObject.NULL).toString())
            handler.postDelayed(this, 5_000)
        }
    }

    var foreground = false
    var state by mutableStateOf("disconnected")
        private set
    val origin: HttpUrl? get() = pairing?.endpoint?.newBuilder()?.encodedPath("/")?.build()
        ?: storage.read("demo")?.let(Pairing::parse)?.endpoint?.newBuilder()?.encodedPath("/")?.build()
    val host get() = origin?.host

    fun resume() {
        foreground = true
        if (pairing == null) {
            pairing = storage.read("demo")?.let(Pairing::parse)
            retry = 0
            open()
        } else hello()
    }

    fun connect(value: String): Boolean {
        val candidate = Pairing.parse(value.trim()) ?: return false
        if (runCatching { storage.write("demo", value.trim()) }.isFailure) return false
        stop()
        pairing = candidate
        retry = 0
        open()
        return true
    }

    fun disconnect() {
        stop()
        storage.write("demo", null)
    }

    private fun stop() {
        pairing = null
        val connection = socket
        socket = null
        connection?.cancel()
        handler.removeCallbacks(heartbeat)
        handler.removeCallbacks(connectLater)
        state = "disconnected"
    }

    private fun installationId(): String = preferences.getString("installationId", null)
        ?: UUID.randomUUID().toString().also { value ->
            preferences.edit { putString("installationId", value) }
        }

    fun hello() {
        if (state != "connected") return
        val info = JSONObject().put("schemaVersion", 2).put("type", "hello")
            .put("installationId", installationId())
            .put("appVersion", BuildConfig.VERSION_NAME)
            .put("preferredLanguage", training.language.id)
            .put("languagePackageVersion", training.language.version)
            .put("resourceVersion", 2)
            .put("liveActivitiesEnabled", false)
            .put("notificationAuthorization", if (training.notificationsEnabled) "authorized" else "denied")
        training.alerts.demoSubscription()?.let { info.put("regionalSubscription", it) }
        socket?.send(info.toString())
    }

    private fun open() {
        val current = pairing ?: return
        state = if (retry == 0) "connecting" else "reconnecting"
        socket = client.newWebSocket(Request.Builder().url(current.endpoint)
            .header("Authorization", "Bearer ${current.token}").build(), object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                handler.post {
                    if (socket !== ws) return@post
                    state = "connected"
                    retry = 0
                    training.log("demo connected")
                    hello()
                    handler.postDelayed(heartbeat, 5_000)
                }
            }

            override fun onMessage(ws: WebSocket, text: String) {
                if (text.toByteArray().size > 16384) {
                    ws.close(1009, "Message too large")
                    return
                }
                handler.post {
                    if (socket !== ws) return@post
                    runCatching { JSONObject(text) }.getOrNull()?.let { wire ->
                        training.ingestDemo(wire)?.let { ws.send(it.toString()) }
                    }
                }
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) = reconnect(ws)
            override fun onClosed(ws: WebSocket, code: Int, reason: String) = reconnect(ws)
            override fun onClosing(ws: WebSocket, code: Int, reason: String) {
                ws.close(code, null)
            }
        })
    }

    private fun reconnect(ws: WebSocket) {
        handler.post {
            if (socket !== ws) return@post
            socket = null
            handler.removeCallbacks(heartbeat)
            state = "reconnecting"
            if (retry == 0) training.log("demo disconnected; retrying")
            val delays = longArrayOf(1, 2, 4, 8, 15, 30)
            handler.postDelayed(connectLater, delays[retry.coerceAtMost(delays.lastIndex)] * 1000)
            if (retry < delays.lastIndex) retry++
        }
    }
}

internal fun instant(value: String?): Long? = runCatching { Instant.parse(value).toEpochMilli() }.getOrNull()
internal fun randomHex(): String = ByteArray(32).also { SecureRandom().nextBytes(it) }
    .joinToString("") { "%02x".format(it) }
