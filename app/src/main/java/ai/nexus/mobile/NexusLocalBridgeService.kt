package ai.nexus.mobile

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.runBlocking

/**
 * User-started, USB-forwarded inference API. It listens on device loopback only.
 * It intentionally exposes inference, not Android UI automation or device control.
 */
class NexusLocalBridgeService : Service() {
    @Volatile private var server: ServerSocket? = null
    private val running = AtomicBoolean(false)
    private val generating = AtomicBoolean(false)
    private val clients = Executors.newSingleThreadExecutor()
    private val workers = Executors.newFixedThreadPool(2)

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, notification())
        running.set(true)
        clients.execute { acceptLoop() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_NOT_STICKY
    }

    private fun acceptLoop() {
        try {
            ServerSocket(PORT, 8, InetAddress.getByName("127.0.0.1")).use { socket ->
                server = socket
                while (running.get()) {
                    val client = try { socket.accept() } catch (_: Exception) { break }
                    workers.execute { handleClient(client) }
                }
            }
        } catch (_: Exception) {
            // Do not log request data, tokens, model prompts, or outputs.
        } finally {
            server = null
            running.set(false)
            stopSelf()
        }
    }

    private fun handleClient(client: java.net.Socket) {
        client.use { connection ->
            try {
                connection.soTimeout = 15_000
                val input = BufferedInputStream(connection.getInputStream())
                val output = BufferedOutputStream(connection.getOutputStream())
                val requestLine = readAsciiLine(input, 2048) ?: return
                val parts = requestLine.split(" ")
                if (parts.size != 3 || !parts[2].startsWith("HTTP/1.")) {
                    respond(output, 400, JSONObject().put("error", "Bad request"))
                    return
                }
                val headers = mutableMapOf<String, String>()
                var totalHeaderBytes = requestLine.length
                while (true) {
                    val line = readAsciiLine(input, 4096) ?: return
                    totalHeaderBytes += line.length
                    if (totalHeaderBytes > 8192) {
                        respond(output, 431, JSONObject().put("error", "Headers too large"))
                        return
                    }
                    if (line.isEmpty()) break
                    val split = line.indexOf(':')
                    if (split > 0) headers[line.substring(0, split).trim().lowercase()] =
                        line.substring(split + 1).trim()
                }
                if (!authorized(headers["authorization"])) {
                    respond(output, 401, JSONObject().put("error", "Unauthorized"))
                    return
                }
                val path = parts[1].substringBefore('?')
                val method = parts[0]
                if (method == "GET" && path == "/v1/status") {
                    val engine = NativeLlamaEngine.getInstance(this)
                    respond(output, 200, JSONObject()
                        .put("ok", true)
                        .put("bridge", if (running.get()) "running" else "stopped")
                        .put("model_loaded", engine.isModelLoaded())
                        .put("app", "NEXUS AI"))
                    return
                }
                if (method != "POST" || path != "/v1/generate") {
                    respond(output, 404, JSONObject().put("error", "Not found"))
                    return
                }
                val length = headers["content-length"]?.toIntOrNull()
                if (length == null || length !in 1..16_384) {
                    respond(output, 413, JSONObject().put("error", "Invalid or oversized body"))
                    return
                }
                val bytes = ByteArray(length)
                var offset = 0
                while (offset < length) {
                    val count = input.read(bytes, offset, length - offset)
                    if (count < 0) throw IllegalArgumentException("Incomplete body")
                    offset += count
                }
                val body = JSONObject(String(bytes, Charsets.UTF_8))
                if (body.length() != 2 || !body.has("prompt") || !body.has("max_tokens")) {
                    respond(output, 400, JSONObject().put("error", "Expected prompt and max_tokens only"))
                    return
                }
                val prompt = body.optString("prompt")
                val maxTokens = body.optInt("max_tokens", -1)
                if (prompt.isBlank() || prompt.length > 8000 || maxTokens !in 16..160) {
                    respond(output, 400, JSONObject().put("error", "Prompt or token limit is invalid"))
                    return
                }
                val engine = NativeLlamaEngine.getInstance(this)
                if (!engine.isModelLoaded()) {
                    respond(output, 409, JSONObject().put("error", "Load a GGUF model in NEXUS first"))
                    return
                }
                if (!generating.compareAndSet(false, true)) {
                    respond(output, 429, JSONObject().put("error", "A generation is already running"))
                    return
                }
                try {
                    val started = System.nanoTime()
                    val text = runBlocking { engine.generate(prompt, maxTokens) }
                    val elapsed = (System.nanoTime() - started) / 1_000_000L
                    respond(output, 200, JSONObject()
                        .put("ok", true).put("text", text).put("generation_ms", elapsed))
                } finally {
                    generating.set(false)
                }
            } catch (_: Exception) {
                // Avoid leaking prompts, outputs, bearer tokens, or private paths.
                try {
                    respond(BufferedOutputStream(connection.getOutputStream()), 400,
                        JSONObject().put("error", "Request could not be processed"))
                } catch (_: Exception) { }
            }
        }
    }

    private fun authorized(header: String?): Boolean {
        val expected = getSharedPreferences(PREFS, MODE_PRIVATE)
            .getString(KEY_TOKEN, null)?.toByteArray(Charsets.UTF_8) ?: return false
        val supplied = header?.takeIf { it.startsWith("Bearer ") }
            ?.removePrefix("Bearer ")?.toByteArray(Charsets.UTF_8) ?: return false
        return MessageDigest.isEqual(expected, supplied)
    }

    private fun readAsciiLine(input: BufferedInputStream, max: Int): String? {
        val bytes = java.io.ByteArrayOutputStream()
        while (bytes.size() <= max) {
            val value = input.read()
            if (value < 0) return if (bytes.size() == 0) null else bytes.toString("US-ASCII")
            if (value == 10) break
            if (value != 13) bytes.write(value)
        }
        if (bytes.size() > max) throw IllegalArgumentException("Line too long")
        return bytes.toString("US-ASCII")
    }

    private fun respond(output: BufferedOutputStream, code: Int, body: JSONObject) {
        val bytes = body.toString().toByteArray(Charsets.UTF_8)
        val reason = when (code) {
            200 -> "OK"; 400 -> "Bad Request"; 401 -> "Unauthorized"
            404 -> "Not Found"; 409 -> "Conflict"; 413 -> "Payload Too Large"
            429 -> "Too Many Requests"; 431 -> "Request Header Fields Too Large"
            else -> "Error"
        }
        val header = "HTTP/1.1 $code $reason\r\nContent-Type: application/json; charset=utf-8\r\n" +
            "Content-Length: ${bytes.size}\r\nConnection: close\r\nCache-Control: no-store\r\n\r\n"
        output.write(header.toByteArray(Charsets.US_ASCII))
        output.write(bytes)
        output.flush()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel(
                CHANNEL_ID, "NEXUS Local Bridge", NotificationManager.IMPORTANCE_LOW
            ))
        }
    }

    private fun notification(): Notification {
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            Notification.Builder(this, CHANNEL_ID) else Notification.Builder(this)
        return builder.setContentTitle("NEXUS Local Bridge активен")
            .setContentText("Локальная модель доступна только через USB ADB и loopback.")
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setOngoing(true).build()
    }

    override fun onDestroy() {
        running.set(false)
        try { server?.close() } catch (_: Exception) { }
        workers.shutdownNow()
        clients.shutdownNow()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_START = "ai.nexus.mobile.LOCAL_BRIDGE_START"
        const val ACTION_STOP = "ai.nexus.mobile.LOCAL_BRIDGE_STOP"
        const val PORT = 8766
        const val PREFS = "nexus_settings"
        const val KEY_TOKEN = "local_bridge_token"
        private const val CHANNEL_ID = "nexus_local_bridge"
        private const val NOTIFICATION_ID = 8766
    }
}
