package ai.nexus.mobile

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** On-device bounded diagnostic journal. Logs are never uploaded automatically. */
object NexusDiagnostics {
    private const val FILE_NAME = "nexus-diagnostics.log"
    private const val MAX_BYTES = 256 * 1024
    private const val MAX_LINES = 500
    private val lock = Any()

    fun record(context: Context, category: String, message: String) {
        val safe = message.replace(Regex("[\\r\\n\\t]+"), " ").take(700)
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
        val line = "$stamp [$category] $safe\n"
        synchronized(lock) {
            try {
                val file = File(context.applicationContext.filesDir, FILE_NAME)
                if (file.exists() && file.length() > MAX_BYTES) {
                    val retained = file.readLines().takeLast(MAX_LINES / 2)
                    file.writeText(retained.joinToString("\n", postfix = "\n"))
                }
                file.appendText(line)
            } catch (_: Exception) {
                // Diagnostics must never interrupt the assistant.
            }
        }
    }

    fun read(context: Context): String = synchronized(lock) {
        try {
            File(context.applicationContext.filesDir, FILE_NAME).takeIf { it.isFile }
                ?.readLines()?.takeLast(MAX_LINES)?.joinToString("\n")
                .orEmpty().ifBlank { "Журнал пока пуст. Отправь команду или запусти голосовое распознавание." }
        } catch (error: Exception) {
            "Не удалось прочитать журнал: ${error.message ?: error.javaClass.simpleName}"
        }
    }

    fun clear(context: Context) = synchronized(lock) {
        try { File(context.applicationContext.filesDir, FILE_NAME).delete() } catch (_: Exception) { }
    }

    fun analyze(context: Context): String {
        val entries = read(context).lines()
        val failures = entries.count { it.contains("[ERROR]") || it.contains("ошибка", true) || it.contains("не удалось", true) || it.contains("FAILED") }
        val slow = entries.filter { Regex("(?:за|время|duration)[^0-9]{0,20}(\\d{4,})\\s*мс", RegexOption.IGNORE_CASE).containsMatchIn(it) }
        val voice = entries.count { it.contains("VOICE") }
        val model = entries.count { it.contains("MODEL") }
        return buildString {
            appendLine("Локальный анализ журнала NEXUS")
            appendLine("Записей: ${entries.count { it.isNotBlank() }}; ошибок/сбоев: $failures.")
            appendLine("Событий голосового модуля: $voice; событий модели: $model.")
            if (slow.isNotEmpty()) {
                appendLine("Обнаружены длительные этапы:")
                slow.takeLast(5).forEach { appendLine("• $it") }
            } else appendLine("Явных замеров длительных этапов пока нет.")
            if (failures > 0) appendLine("Рекомендация: скопируй журнал целиком и передай его для точечного исправления.")
            if (voice == 0) appendLine("Пока нет событий голосового модуля: проверь разрешение микрофона и языковой пакет распознавания.")
            if (model > 0) appendLine("Для ускорения генерации важны название/квантование GGUF и время генерации токенов; эти данные будут записываться в следующих запросах.")
            appendLine("Анализ выполняется локально по событиям; приложение не переписывает собственный код и не меняет настройки без подтверждения.")
        }
    }
}
