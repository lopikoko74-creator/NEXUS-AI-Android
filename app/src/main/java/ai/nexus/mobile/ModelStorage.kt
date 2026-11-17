package ai.nexus.mobile

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.os.StatFs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

/**
 * Imports a user-selected GGUF into private app storage.
 * Streaming copy avoids loading multi-gigabyte model files into memory.
 */
object ModelStorage {
    suspend fun importModel(context: Context, uri: Uri): File = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val displayName = resolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME),
            null,
            null,
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        } ?: "local-model.gguf"

        if (!displayName.lowercase().endsWith(".gguf")) {
            throw IllegalArgumentException("Выбранный файл не имеет расширение .gguf")
        }

        val expectedSize = resolver.query(
            uri,
            arrayOf(OpenableColumns.SIZE),
            null,
            null,
            null
        )?.use { cursor ->
            if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0) else -1L
        } ?: -1L

        val modelDir = File(context.filesDir, "models").apply { mkdirs() }
        val available = StatFs(modelDir.absolutePath).availableBytes
        if (expectedSize > 0 && available < expectedSize + 32L * 1024L * 1024L) {
            throw IllegalStateException("Недостаточно свободного места для копирования модели")
        }

        val safeBase = displayName.substringBeforeLast('.')
            .replace(Regex("[^A-Za-z0-9._-]"), "_")
            .take(48)
            .ifBlank { "model" }
        val target = File(modelDir, "$safeBase-${UUID.randomUUID()}.gguf")
        val temporary = File(modelDir, "${target.name}.partial")

        try {
            val input = resolver.openInputStream(uri)
                ?: throw IllegalStateException("Не удалось открыть выбранный файл")
            input.use { source ->
                val output = FileOutputStream(temporary)
                output.use { sink ->
                    val header = ByteArray(4)
                    var headerRead = 0
                    while (headerRead < header.size) {
                        val count = source.read(header, headerRead, header.size - headerRead)
                        if (count < 0) break
                        headerRead += count
                    }
                    if (headerRead != 4 || String(header, Charsets.US_ASCII) != "GGUF") {
                        throw IllegalArgumentException("Файл не прошёл проверку заголовка GGUF")
                    }
                    sink.write(header)
                    val buffer = ByteArray(1024 * 1024)
                    while (true) {
                        if (StatFs(modelDir.absolutePath).availableBytes < 16L * 1024L * 1024L) {
                            throw IllegalStateException("Недостаточно места: импорт остановлен до заполнения памяти")
                        }
                        val count = source.read(buffer)
                        if (count < 0) break
                        sink.write(buffer, 0, count)
                    }
                    sink.fd.sync()
                }
            }
            if (!temporary.renameTo(target)) {
                throw IllegalStateException("Не удалось завершить импорт модели")
            }
            target
        } catch (error: Throwable) {
            temporary.delete()
            target.delete()
            throw error
        }
    }
}
