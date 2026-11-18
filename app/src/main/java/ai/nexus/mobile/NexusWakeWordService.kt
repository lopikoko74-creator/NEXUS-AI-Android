package ai.nexus.mobile

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import java.util.Locale

/**
 * User-started foreground microphone service.
 * Uses Android's on-device recognizer only; never silently falls back to a network recognizer.
 */
class NexusWakeWordService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var waitingForCommand = false
    private var stopped = false

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopListening()
            stopSelf()
            return START_NOT_STICKY
        }

        val notification = buildNotification("Ожидаю слово «NEXUS»")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceCompat.startForeground(
                this, NOTIFICATION_ID, notification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            !SpeechRecognizer.isOnDeviceRecognitionAvailable(this)) {
            updateNotification("Нет встроенного офлайн-распознавания русского языка")
            broadcastStatus("На устройстве недоступен системный офлайн-распознаватель. Фоновое ожидание не запущено.")
            stopSelf()
            return START_NOT_STICKY
        }

        if (recognizer == null) {
            recognizer = SpeechRecognizer.createOnDeviceSpeechRecognizer(this).also {
                it.setRecognitionListener(listener)
            }
        }
        stopped = false
        startListening()
        return START_NOT_STICKY
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: android.os.Bundle?) {
            broadcastStatus(if (waitingForCommand) "NEXUS услышал. Скажи команду (до 10 секунд)." else "Фоновое ожидание: скажи «NEXUS».")
        }
        override fun onBeginningOfSpeech() = Unit
        override fun onRmsChanged(rmsdB: Float) = Unit
        override fun onBufferReceived(buffer: ByteArray?) = Unit
        override fun onEndOfSpeech() = Unit
        override fun onError(error: Int) {
            if (stopped) return
            if (waitingForCommand && error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
                waitingForCommand = false
                updateNotification("Ожидаю слово «NEXUS»")
                broadcastStatus("Время команды истекло. Снова ожидаю «NEXUS».")
            }
            handler.postDelayed({ startListening() }, 450L)
        }
        override fun onResults(results: android.os.Bundle?) {
            if (stopped) return
            val phrase = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()?.trim().orEmpty()
            val normalized = phrase.lowercase(Locale.forLanguageTag("ru-RU"))
                .replace("ё", "е").replace(Regex("[^\\p{L}\\p{N} ]"), " ")
                .trim()
            if (!waitingForCommand) {
                val wakeDetected = normalized.split(Regex("\\s+")).any {
                    it in setOf("nexus", "нексус", "некcус", "некус")
                }
                if (wakeDetected) {
                    waitingForCommand = true
                    updateNotification("NEXUS активирован — скажи команду")
                    broadcastStatus("Слушаю команду. У тебя 10 секунд.")
                    handler.removeCallbacks(commandTimeout)
                    handler.postDelayed(commandTimeout, COMMAND_WINDOW_MS)
                }
            } else if (phrase.isNotBlank()) {
                waitingForCommand = false
                handler.removeCallbacks(commandTimeout)
                val command = phrase.replace(Regex("(?i)^\\s*(nexus|нексус|некус)[, ]*"), "").trim()
                if (command.isNotBlank()) {
                    val event = Intent(ACTION_COMMAND).setPackage(packageName).putExtra(EXTRA_COMMAND, command)
                    sendBroadcast(event)
                    broadcastStatus("Команда передана NEXUS.")
                } else {
                    broadcastStatus("Команда не распознана. Снова ожидаю «NEXUS».")
                }
                updateNotification("Ожидаю слово «NEXUS»")
            }
            handler.postDelayed({ startListening() }, 350L)
        }
        override fun onPartialResults(partialResults: android.os.Bundle?) = Unit
        override fun onEvent(eventType: Int, params: android.os.Bundle?) = Unit
    }

    private val commandTimeout = Runnable {
        if (waitingForCommand && !stopped) {
            waitingForCommand = false
            updateNotification("Ожидаю слово «NEXUS»")
            broadcastStatus("Окно команды закрыто. Снова ожидаю «NEXUS».")
            try { recognizer?.cancel() } catch (_: Exception) {}
            handler.postDelayed({ startListening() }, 300L)
        }
    }

    private fun startListening() {
        if (stopped || waitingForCommand && !recognizerReadyForCommand()) return
        val current = recognizer ?: return
        try {
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ru-RU")
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "ru-RU")
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 2)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 900)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 500)
            }
            current.startListening(intent)
        } catch (_: Exception) {
            handler.postDelayed({ startListening() }, 1200L)
        }
    }

    private fun recognizerReadyForCommand() = true

    private fun stopListening() {
        stopped = true
        waitingForCommand = false
        handler.removeCallbacksAndMessages(null)
        try { recognizer?.cancel(); recognizer?.destroy() } catch (_: Exception) {}
        recognizer = null
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
    }

    private fun broadcastStatus(message: String) {
        sendBroadcast(Intent(ACTION_STATUS).setPackage(packageName).putExtra(EXTRA_STATUS, message))
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "NEXUS — голосовое ожидание", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    private fun buildNotification(message: String): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("NEXUS AI")
            .setContentText(message)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()

    private fun updateNotification(message: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(message))
    }

    override fun onDestroy() {
        stopListening()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_START = "ai.nexus.mobile.action.START_WAKE_WORD"
        const val ACTION_STOP = "ai.nexus.mobile.action.STOP_WAKE_WORD"
        const val ACTION_COMMAND = "ai.nexus.mobile.action.WAKE_COMMAND"
        const val ACTION_STATUS = "ai.nexus.mobile.action.WAKE_STATUS"
        const val EXTRA_COMMAND = "command"
        const val EXTRA_STATUS = "status"
        private const val CHANNEL_ID = "nexus_wake_word"
        private const val NOTIFICATION_ID = 4201
        private const val COMMAND_WINDOW_MS = 10_000L
    }
}
