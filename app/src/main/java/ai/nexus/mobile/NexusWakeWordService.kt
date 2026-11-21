package ai.nexus.mobile

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import kotlinx.coroutines.withContext
import android.net.Uri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import java.util.Locale

/**
 * User-started foreground microphone service.
 * Recognition is strictly serialized: only one listening session and one
 * scheduled restart may exist at a time.
 */
class NexusWakeWordService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var waitingForCommand = false
    private var executingCommand = false
    private var stopped = true
    private var listening = false
    private var restartScheduled = false
    private var consecutiveErrors = 0
    private var finalResultPending = false
    private var sessionResolved = false
    private var latestPartial = ""
    private var floatingOverlay: NexusFloatingOrbOverlay? = null
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var textToSpeech: TextToSpeech? = null
    private var ttsReady = false

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        textToSpeech = TextToSpeech(this) { status ->
            ttsReady = status == TextToSpeech.SUCCESS
            if (ttsReady) textToSpeech?.language = Locale("ru", "RU")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopListening()
            stopSelf()
            return START_NOT_STICKY
        }

        if (!stopped && recognizer != null) {
            // Repeated START intents must not create another recognizer loop.
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

        stopped = false
        waitingForCommand = false
        consecutiveErrors = 0
        createRecognizer()
        scheduleRestart(700L)
        return START_NOT_STICKY
    }

    private fun createRecognizer() {
        if (stopped) return
        destroyRecognizer()
        recognizer = SpeechRecognizer.createOnDeviceSpeechRecognizer(this).also {
            it.setRecognitionListener(listener)
        }
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: android.os.Bundle?) {
            listening = true
            if (waitingForCommand) {
                // Start the command window only when the recognizer is actually ready.
                handler.removeCallbacks(commandTimeout)
                handler.postDelayed(commandTimeout, COMMAND_WINDOW_MS)
            }
            broadcastStatus(if (waitingForCommand) "NEXUS услышал. Скажи команду (до 10 секунд)." else "Фоновое ожидание: скажи «NEXUS».")
        }
        override fun onBeginningOfSpeech() = Unit
        override fun onRmsChanged(rmsdB: Float) = Unit
        override fun onBufferReceived(buffer: ByteArray?) = Unit
        override fun onEndOfSpeech() {
            listening = false
            handler.removeCallbacks(commandTimeout)
            finalResultPending = true
            NexusDiagnostics.record(this@NexusWakeWordService, "VOICE", "onEndOfSpeech: ожидание финального результата")
            broadcastStatus("Обрабатываю речь…")
            handler.removeCallbacks(finalResultWatchdog)
            handler.postDelayed(finalResultWatchdog, FINAL_RESULT_TIMEOUT_MS)
        }
        override fun onError(error: Int) {
            if (stopped || sessionResolved) return
            handler.removeCallbacks(finalResultWatchdog)
            finalResultPending = false
            NexusDiagnostics.record(this@NexusWakeWordService, "VOICE_ERROR", "SpeechRecognizer onError code=$error")
            listening = false
            if (waitingForCommand && error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
                waitingForCommand = false
                handler.removeCallbacks(commandTimeout)
                updateNotification("Ожидаю слово «NEXUS»")
                broadcastStatus("Время команды истекло. Снова ожидаю «NEXUS».")
            }
            consecutiveErrors++
            val message = when (error) {
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Нет разрешения на микрофон. Голосовое ожидание остановлено."
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Распознаватель занят. Перезапускаю голосовой модуль."
                SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT ->
                    "Офлайн-распознавание недоступно. Проверь установленный русский языковой пакет."
                else -> "Сбой распознавания (код $error). Повторная попытка через несколько секунд."
            }
            broadcastStatus(message)
            if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
                stopSelf()
            } else {
                if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY || error == SpeechRecognizer.ERROR_CLIENT) {
                    handler.post { if (!stopped) createRecognizer() }
                }
                scheduleRestart(backoffDelay())
            }
        }
        override fun onResults(results: android.os.Bundle?) {
            if (stopped || sessionResolved) return
            handler.removeCallbacks(finalResultWatchdog)
            handler.removeCallbacks(partialFinalize)
            finalResultPending = false
            sessionResolved = true
            listening = false
            consecutiveErrors = 0
            val phrase = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()?.trim().orEmpty()
            NexusDiagnostics.record(this@NexusWakeWordService, "VOICE_RESULT", if (phrase.isBlank()) "Получен пустой финальный результат" else "Получен финальный результат (${phrase.length} символов)")
            handleRecognizedPhrase(phrase)
            scheduleRestart(900L)
        }
        override fun onPartialResults(partialResults: android.os.Bundle?) {
            if (stopped || sessionResolved) return
            val partial = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim().orEmpty()
            if (partial.isBlank()) return
            latestPartial = partial
            NexusDiagnostics.record(this@NexusWakeWordService, "VOICE_PARTIAL", "Получен частичный результат (${partial.length} символов)")
            // Some on-device recognizers stream partial hypotheses forever and never
            // deliver onResults. Finalize the last stable hypothesis after silence.
            handler.removeCallbacks(partialFinalize)
            handler.postDelayed(partialFinalize, PARTIAL_FINALIZE_DELAY_MS)
        }
        override fun onEvent(eventType: Int, params: android.os.Bundle?) = Unit
    }


    private val partialFinalize: Runnable = Runnable {
        if (!stopped && !sessionResolved && latestPartial.isNotBlank()) {
            val phrase = latestPartial.trim()
            sessionResolved = true
            listening = false
            finalResultPending = false
            handler.removeCallbacks(finalResultWatchdog)
            NexusDiagnostics.record(this, "VOICE_FALLBACK", "Частичная гипотеза финализирована после " + PARTIAL_FINALIZE_DELAY_MS + " мс тишины")
            try { recognizer?.cancel() } catch (_: Exception) {}
            handleRecognizedPhrase(phrase)
            scheduleRestart(900L)
        }
    }

    private fun handleRecognizedPhrase(phrase: String) {
        val parsed = WakeWordCommandParser.parse(phrase, waitingForCommand)
        if (!waitingForCommand) {
            if (!parsed.wakeWordDetected) {
                broadcastStatus("Ключевое слово не обнаружено. Продолжаю ожидание.")
                return
            }
            val inlineCommand = parsed.command
            if (inlineCommand.isNullOrBlank()) {
                waitingForCommand = true
                updateNotification("NEXUS активирован — скажи команду")
                showFloatingOrb()
                broadcastStatus(if (floatingOverlay != null) "Слушаю команду. У тебя 10 секунд." else "Слушаю команду. Для шара поверх рабочего стола разреши NEXUS показываться поверх других приложений.")
                return
            }
            // One-breath command: preserve everything spoken after the wake word.
            waitingForCommand = false
            hideFloatingOrb()
            deliverCommand(inlineCommand)
            updateNotification("Ожидаю слово «NEXUS»")
            broadcastStatus("Команда передана NEXUS.")
            return
        }

        waitingForCommand = false
        handler.removeCallbacks(commandTimeout)
        hideFloatingOrb()
        val command = parsed.command
        if (!command.isNullOrBlank()) {
            deliverCommand(command)
            broadcastStatus("Команда передана NEXUS.")
        } else {
            broadcastStatus("Команда не распознана. Снова ожидаю «NEXUS».")
        }
        updateNotification("Ожидаю слово «NEXUS»")
    }

    private val finalResultWatchdog: Runnable = Runnable {
        if (!stopped && finalResultPending) {
            finalResultPending = false
            sessionResolved = true
            handler.removeCallbacks(commandTimeout)
            if (waitingForCommand) {
                waitingForCommand = false
                hideFloatingOrb()
                updateNotification("Ожидаю слово «NEXUS»")
            }
            NexusDiagnostics.record(this, "VOICE_ERROR", "Тайм-аут: onEndOfSpeech не получил onResults/onError за ${FINAL_RESULT_TIMEOUT_MS} мс; перезапускаю распознаватель")
            broadcastStatus("Распознаватель завис после окончания речи. Перезапускаю голосовой модуль.")
            consecutiveErrors++
            createRecognizer()
            scheduleRestart(backoffDelay())
        }
    }

    private val commandTimeout: Runnable = Runnable {
        if (waitingForCommand && !stopped) {
            waitingForCommand = false
            hideFloatingOrb()
            updateNotification("Ожидаю слово «NEXUS»")
            broadcastStatus("Окно команды закрыто. Снова ожидаю «NEXUS».")
            handler.removeCallbacks(finalResultWatchdog)
            finalResultPending = false
            // Mark this session resolved before cancel(): Android may report
            // ERROR_CLIENT for our own cancellation; that is not a real failure.
            sessionResolved = true
            try { recognizer?.cancel() } catch (_: Exception) {}
            listening = false
            scheduleRestart(900L)
        }
    }


    private fun showFloatingOrb() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M ||
            !android.provider.Settings.canDrawOverlays(this)) {
            broadcastStatus("NEXUS активирован. Разреши показ поверх других приложений в настройках NEXUS, чтобы видеть шар на рабочем столе.")
            return
        }
        val overlay = floatingOverlay ?: NexusFloatingOrbOverlay(this).also { floatingOverlay = it }
        if (!overlay.show()) {
            broadcastStatus("Не удалось показать плавающий шар. Проверь разрешение «Поверх других приложений».")
        }
    }

    private fun hideFloatingOrb() {
        floatingOverlay?.hide()
        floatingOverlay = null
    }

    /**
     * Execute recognized requests inside the foreground service. The launcher
     * Activity is deliberately not opened for ordinary voice commands.
     */
    private fun deliverCommand(command: String) {
        hideFloatingOrb()
        executingCommand = true
        restartScheduled = false
        handler.removeCallbacksAndMessages(null)
        try { recognizer?.cancel() } catch (_: Exception) {}
        listening = false
        serviceScope.launch {
            updateNotification("Выполняю команду…")
            broadcastStatus("Команда принята. Выполняю без открытия главного окна.")
            val resultText = try {
                executeVoiceRequest(command)
            } catch (error: Exception) {
                NexusDiagnostics.record(this@NexusWakeWordService, "AGENT_ERROR", error.stackTraceToString().take(1800))
                "Не удалось выполнить команду: ${error.message ?: error.javaClass.simpleName}"
            }
            updateNotification("Ожидаю слово «NEXUS»")
            broadcastStatus(resultText)
            speak(resultText)
            executingCommand = false
            scheduleRestart(900L)
        }
    }

    private suspend fun executeVoiceRequest(request: String): String {
        AppCommandParser.parseYouTubeSearch(request)?.let { search ->
            val uri = Uri.parse("https://www.youtube.com/results").buildUpon()
                .appendQueryParameter("search_query", search.query).build()
            return try {
                startActivity(Intent(Intent.ACTION_VIEW, uri)
                    .setPackage("com.google.android.youtube")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                "Открываю YouTube. Ищу: ${search.query}."
            } catch (_: android.content.ActivityNotFoundException) {
                startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                "Открываю поиск YouTube в браузере."
            }
        }

        val apps = InstalledAppLauncher.listLaunchableApps(this)
        AppCommandParser.parse(request, apps)?.let { parsed ->
            return when (val launched = InstalledAppLauncher.launch(this, parsed.appName)) {
                is InstalledAppLauncher.Result.Launched -> "Открыл приложение «${launched.appLabel}»."
                is InstalledAppLauncher.Result.NotFound -> "Не нашёл установленное приложение «${launched.requestedName}»."
                is InstalledAppLauncher.Result.Failed -> "Не удалось открыть приложение: ${launched.reason}"
            }
        }

        val engine = NativeLlamaEngine.getInstance(this)
        if (!engine.isModelLoaded()) {
            val model = getSharedPreferences("nexus_settings", MODE_PRIVATE)
                .getString("model_file", null)?.let(::File)
            if (model == null || !model.isFile || !model.canRead()) {
                return "Команду услышал, но локальная модель не подготовлена. Открой NEXUS и один раз выбери GGUF-файл."
            }
            broadcastStatus("Загружаю сохранённую локальную модель…")
            updateNotification("Загружаю локальную модель…")
            engine.loadModel(model)
        }

        val screen = NexusAccessibilityService.current?.describeActiveWindow()
            ?: "Служба специальных возможностей не подключена."
        val observations = buildList {
            add("Installed launchable apps (exact visible labels): ${apps.joinToString(", ").take(1800)}")
            if (apps.any { it.equals("MAX", ignoreCase = true) }) {
                add("Voice alias: when the user says «Макс» as an app name, it refers to the installed app label «MAX».")
            }
            add("Initial screen: ${screen.take(1200)}")
        }
        val loop = NexusAgentLoop(
            generate = { prompt -> engine.generate(prompt, 128) },
            execute = { plan, userConfirmed ->
                when (plan) {
                    is NexusAiPlan.OpenApp -> when (val opened = InstalledAppLauncher.launch(this@NexusWakeWordService, plan.appName)) {
                        is InstalledAppLauncher.Result.Launched -> {
                            // Opening an app is an intermediate step when the user
                            // asked for something else inside it (e.g. find a chat).
                            delay(900L)
                            val currentScreen = NexusAccessibilityService.current?.describeActiveWindow()
                                ?: "Служба специальных возможностей не подключена."
                            NexusExecutionOutcome(true, "Открыл приложение «${opened.appLabel}». Текущий экран: ${currentScreen.take(900)}")
                        }
                        is InstalledAppLauncher.Result.NotFound -> NexusExecutionOutcome(false, "Приложение «${opened.requestedName}» не найдено.")
                        is InstalledAppLauncher.Result.Failed -> NexusExecutionOutcome(false, opened.reason)
                    }
                    is NexusAiPlan.Scroll -> {
                        val access = NexusAccessibilityService.current
                        if (access == null) NexusExecutionOutcome(false, "Не подключена служба специальных возможностей.")
                        else {
                            val ok = access.executeApprovedAction(NexusAction.Scroll(plan.direction == "FORWARD"), true)
                            NexusExecutionOutcome(ok, if (ok) "Экран прокручен. ${access.describeActiveWindow()}" else "Не удалось прокрутить текущий экран.")
                        }
                    }
                    NexusAiPlan.GoBack -> {
                        val access = NexusAccessibilityService.current
                        if (access == null) NexusExecutionOutcome(false, "Не подключена служба специальных возможностей.")
                        else {
                            val ok = access.executeApprovedAction(NexusAction.GoBack, true)
                            NexusExecutionOutcome(ok, if (ok) "Вернулся назад." else "Android не выполнил действие «Назад».")
                        }
                    }
                    is NexusAiPlan.Wait -> {
                        delay(plan.milliseconds.toLong())
                        NexusExecutionOutcome(true, "Подождал ${plan.milliseconds} мс. Экран: ${NexusAccessibilityService.current?.describeActiveWindow() ?: "недоступен"}")
                    }
                    is NexusAiPlan.TapText -> {
                        if (!userConfirmed) {
                            NexusExecutionOutcome(false, "Подтверди нажатие «${plan.exactLabel}» в приложении NEXUS.", requiresConfirmation = true)
                        } else {
                            val access = NexusAccessibilityService.current
                            val ok = access?.executeApprovedAction(NexusAction.TapVisibleText(plan.exactLabel), true) == true
                            NexusExecutionOutcome(ok, if (ok) "Нажал «${plan.exactLabel}». Экран: ${access?.describeActiveWindow() ?: "недоступен"}" else "Не удалось нажать «${plan.exactLabel}».")
                        }
                    }
                    is NexusAiPlan.EnterText -> {
                        if (!userConfirmed) {
                            NexusExecutionOutcome(false, "Подтверди ввод текста в приложении NEXUS.", requiresConfirmation = true)
                        } else {
                            val access = NexusAccessibilityService.current
                            val ok = access?.executeApprovedAction(NexusAction.EnterText(plan.text), true) == true
                            NexusExecutionOutcome(ok, if (ok) "Текст введён." else "Не удалось ввести текст в активное поле.")
                        }
                    }
                    is NexusAiPlan.Answer, is NexusAiPlan.Done, is NexusAiPlan.Unsupported ->
                        NexusExecutionOutcome(false, "Ответ обрабатывается планировщиком.")
                }
            },
            maxSteps = 10,
            onEvent = { event ->
                when (event.type) {
                    NexusAgentEventType.THINKING -> broadcastStatus(event.title)
                    NexusAgentEventType.ACTION_STARTED -> broadcastStatus(event.title + ": " + event.detail)
                    else -> Unit
                }
            }
        )
        return when (val result = loop.run(request, observations)) {
            is NexusAgentResult.Completed -> result.summary
            is NexusAgentResult.Unsupported -> result.reason
            is NexusAgentResult.Failed -> result.reason
            is NexusAgentResult.AwaitingConfirmation -> {
                NexusPendingActionStore.pending = result.pending
                updateNotification("Нужно подтверждение. Нажми, чтобы продолжить.")
                result.explanation + " Нажми на уведомление NEXUS, чтобы подтвердить действие."
            }
        }
    }

    private suspend fun speak(message: String) {
        if (message.isBlank()) return
        try {
            NexusNeuralVoice.speak(this, message)
            NexusDiagnostics.record(this, "VOICE_TTS", "Ответ озвучен локальным нейросетевым голосом Piper Denis")
        } catch (error: Exception) {
            NexusDiagnostics.record(this, "VOICE_TTS_FALLBACK", error.stackTraceToString().take(1200))
            withContext(Dispatchers.Main.immediate) {
                if (ttsReady) textToSpeech?.speak(message, TextToSpeech.QUEUE_FLUSH, null, "nexus-service-response")
                broadcastStatus("Нейросетевой голос пока недоступен. Использую системный голос.")
            }
        }
    }

    private fun backoffDelay(): Long = when (consecutiveErrors) {
        0, 1 -> 1_500L
        2 -> 3_000L
        3 -> 5_000L
        else -> 10_000L
    }

    private fun scheduleRestart(delayMs: Long) {
        if (stopped || executingCommand || restartScheduled) return
        restartScheduled = true
        handler.postDelayed({
            restartScheduled = false
            startListening()
        }, delayMs)
    }

    private fun startListening() {
        if (stopped || executingCommand || listening || restartScheduled) return
        val current = recognizer ?: run {
            createRecognizer()
            recognizer
        } ?: return
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ru-RU")
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "ru-RU")
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 2)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1_500)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 900)
        }
        try {
            sessionResolved = false
            latestPartial = ""
            listening = true
            NexusDiagnostics.record(this, "VOICE", "startListening: ожидание ${if (waitingForCommand) "команды" else "ключевого слова"}")
            current.startListening(intent)
        } catch (error: Exception) {
            listening = false
            consecutiveErrors++
            broadcastStatus("Не удалось запустить распознавание: ${error.message ?: error.javaClass.simpleName}")
            scheduleRestart(backoffDelay())
        }
    }

    private fun destroyRecognizer() {
        listening = false
        try { recognizer?.cancel() } catch (_: Exception) {}
        try { recognizer?.destroy() } catch (_: Exception) {}
        recognizer = null
    }

    private fun stopListening() {
        stopped = true
        waitingForCommand = false
        hideFloatingOrb()
        restartScheduled = false
        handler.removeCallbacksAndMessages(null)
        destroyRecognizer()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
    }

    private fun broadcastStatus(message: String) {
        sendBroadcast(Intent(ACTION_STATUS).setPackage(packageName).putExtra(EXTRA_STATUS, message))
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "NEXUS — голосовое ожидание", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    private fun buildNotification(message: String): Notification {
        val openApp = PendingIntent.getActivity(
            this, 4202, Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentIntent(openApp)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("NEXUS AI")
            .setContentText(message)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun updateNotification(message: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(message))
    }

    override fun onDestroy() {
        stopListening()
        serviceScope.cancel()
        textToSpeech?.stop()
        textToSpeech?.shutdown()
        textToSpeech = null
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
        private const val FINAL_RESULT_TIMEOUT_MS = 8_000L
        private const val PARTIAL_FINALIZE_DELAY_MS = 1_600L
    }
}
