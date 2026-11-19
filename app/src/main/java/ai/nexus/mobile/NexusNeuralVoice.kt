package ai.nexus.mobile

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean

/** Offline Russian neural voice. Runtime: Apache-2.0 sherpa-onnx; voice: Piper Denis. */
object NexusNeuralVoice {
    private const val MODEL_ID = "vits-piper-ru_RU-denis-medium"
    private const val ARCHIVE_URL = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/$MODEL_ID.tar.bz2"
    private const val MODEL_FILE = "ru_RU-denis-medium.onnx"
    private const val MAX_ARCHIVE_BYTES = 180L * 1024L * 1024L
    private const val MAX_EXTRACTED_BYTES = 240L * 1024L * 1024L
    private val speaking = AtomicBoolean(false)
    @Volatile private var engine: OfflineTts? = null
    @Volatile private var engineDirectory: File? = null

    suspend fun speak(context: Context, text: String) = withContext(Dispatchers.IO) {
        if (text.isBlank() || !speaking.compareAndSet(false, true)) return@withContext
        var track: AudioTrack? = null
        try {
            val dir = ensureVoiceInstalled(context.applicationContext)
            val tts = getEngine(dir)
            val audio = tts.generate(prepareForDelivery(text), sid = 0, speed = 0.94f)
            check(audio.samples.isNotEmpty() && audio.sampleRate > 0) { "Neural voice returned empty audio" }
            val format = AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                .setSampleRate(audio.sampleRate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build()
            val attrs = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
            track = AudioTrack.Builder().setAudioAttributes(attrs).setAudioFormat(format)
                .setTransferMode(AudioTrack.MODE_STATIC).setBufferSizeInBytes(audio.samples.size * 4).build()
            check(track.write(audio.samples, 0, audio.samples.size, AudioTrack.WRITE_BLOCKING) > 0)
            track.play()
            delay(audio.samples.size * 1000L / audio.sampleRate + 180L)
        } finally {
            try { track?.stop() } catch (_: Exception) {}
            track?.release()
            speaking.set(false)
        }
    }

    private fun getEngine(dir: File): OfflineTts {
        engine?.takeIf { engineDirectory == dir }?.let { return it }
        synchronized(this) {
            engine?.takeIf { engineDirectory == dir }?.let { return it }
            try { engine?.release() } catch (_: Exception) {}
            val model = File(dir, MODEL_FILE)
            val tokens = File(dir, "tokens.txt")
            val espeak = File(dir, "espeak-ng-data")
            check(model.isFile && tokens.isFile && espeak.isDirectory) { "Voice model files are incomplete" }
            val config = OfflineTtsConfig(
                model = OfflineTtsModelConfig(
                    vits = OfflineTtsVitsModelConfig(
                        model = model.absolutePath, tokens = tokens.absolutePath,
                        dataDir = espeak.absolutePath, noiseScale = 0.55f,
                        noiseScaleW = 0.75f, lengthScale = 1.0f
                    ),
                    numThreads = 2, debug = false, provider = "cpu"
                ),
                maxNumSentences = 1, silenceScale = 0.28f
            )
            return OfflineTts(config = config).also { engine = it; engineDirectory = dir }
        }
    }

    private fun ensureVoiceInstalled(context: Context): File {
        val root = File(context.filesDir, "nexus-voice/$MODEL_ID")
        if (isVoiceReady(root)) return root
        root.mkdirs()
        val part = File(context.cacheDir, "$MODEL_ID.tar.bz2.part")
        val archive = File(context.cacheDir, "$MODEL_ID.tar.bz2")
        try {
            download(ARCHIVE_URL, part)
            check(part.length() in 1..MAX_ARCHIVE_BYTES) { "Voice archive size is invalid" }
            archive.delete()
            check(part.renameTo(archive)) { "Could not save voice archive" }
            extractSafely(archive, root)
            val voiceDirectory = root.walkTopDown().firstOrNull { isVoiceReady(it) }
                ?: error("Required voice files are missing")
            archive.delete()
            return voiceDirectory
        } catch (e: Exception) {
            part.delete()
            throw IllegalStateException("Не удалось загрузить офлайн-голос. Проверь интернет и повтори команду.", e)
        }
    }

    private fun isVoiceReady(dir: File): Boolean =
        File(dir, MODEL_FILE).let { it.isFile && it.length() > 1_000_000L } &&
            File(dir, "tokens.txt").isFile && File(dir, "espeak-ng-data").isDirectory

    private fun download(url: String, destination: File) {
        destination.parentFile?.mkdirs()
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 20_000
        connection.readTimeout = 45_000
        connection.instanceFollowRedirects = true
        connection.setRequestProperty("User-Agent", "NEXUS-AI-Android")
        try {
            connection.connect()
            check(connection.responseCode in 200..299) { "HTTP \${connection.responseCode} while downloading voice" }
            if (connection.contentLengthLong > 0) check(connection.contentLengthLong <= MAX_ARCHIVE_BYTES)
            connection.inputStream.use { input ->
                FileOutputStream(destination).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        total += n
                        check(total <= MAX_ARCHIVE_BYTES) { "Voice archive exceeds size limit" }
                        output.write(buffer, 0, n)
                    }
                    output.fd.sync()
                }
            }
        } finally { connection.disconnect() }
    }

    private fun extractSafely(archive: File, destination: File) {
        val root = destination.canonicalFile.toPath()
        var total = 0L
        BZip2CompressorInputStream(BufferedInputStream(archive.inputStream())).use { bz ->
            TarArchiveInputStream(bz).use { tar ->
                while (true) {
                    val entry = tar.nextTarEntry ?: break
                    val out = File(destination, entry.name).canonicalFile
                    check(out.toPath().startsWith(root)) { "Unsafe path in voice archive" }
                    if (entry.isDirectory) out.mkdirs()
                    else if (entry.isFile) {
                        total += entry.size
                        check(total <= MAX_EXTRACTED_BYTES) { "Extracted voice exceeds size limit" }
                        out.parentFile?.mkdirs()
                        FileOutputStream(out).use { tar.copyTo(it) }
                    }
                }
            }
        }
    }

    private fun prepareForDelivery(text: String): String = text.replace(Regex("\\s+"), " ").trim()
}
