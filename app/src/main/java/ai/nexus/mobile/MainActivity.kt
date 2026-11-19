package ai.nexus.mobile

import android.content.Intent
import android.content.ClipData
import android.content.ClipboardManager
import android.widget.Toast
import android.content.IntentFilter
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import kotlin.math.PI
import kotlin.math.sin
import androidx.compose.runtime.DisposableEffect
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import java.io.File
import java.util.Locale

private enum class ChatRole { USER, ASSISTANT, ACTIVITY }

private data class ChatLine(
    val id: Long,
    val role: ChatRole,
    val title: String,
    val body: String = ""
)

class MainActivity : ComponentActivity() {
    private var agentJob: Job? = null
    companion object {
        const val EXTRA_WAKE_WORD_OPEN = "nexus_wake_word_open"
        const val EXTRA_WAKE_WORD_COMMAND = "nexus_wake_word_command"
    }
    private var textToSpeech: TextToSpeech? = null
    private fun createAgentLoop(onEvent: (NexusAgentEvent) -> Unit = {}): NexusAgentLoop {
        val engine = NativeLlamaEngine.getInstance(this)
        return NexusAgentLoop(
            generate = { prompt -> engine.generate(prompt, 128) },
            execute = { plan, userConfirmed ->
                when (plan) {
                    is NexusAiPlan.OpenApp -> when (val result = InstalledAppLauncher.launch(this, plan.appName)) {
                        is InstalledAppLauncher.Result.Launched ->
                            NexusExecutionOutcome(true, "Открыл приложение «${result.appLabel}».")
                        is InstalledAppLauncher.Result.NotFound ->
                            NexusExecutionOutcome(false, "Не нашёл установленное приложение с названием «${result.requestedName}».")
                        is InstalledAppLauncher.Result.Failed ->
                            NexusExecutionOutcome(false, "Android не разрешил запуск приложения: ${result.reason}")
                    }
                    is NexusAiPlan.TapText -> {
                        val service = NexusAccessibilityService.current
                        if (!userConfirmed) NexusExecutionOutcome(false, "Нужно подтверждение нажатия по точной надписи «${plan.exactLabel}».", requiresConfirmation = true)
                        else if (service == null) NexusExecutionOutcome(false, "Служба специальных возможностей NEXUS не подключена.")
                        else {
                            val ok = service.executeApprovedAction(NexusAction.TapVisibleText(plan.exactLabel), true)
                            NexusExecutionOutcome(ok, if (ok) "Нажал «${plan.exactLabel}». Экран: ${service.describeActiveWindow()}" else "Не удалось нажать «${plan.exactLabel}». Экран: ${service.describeActiveWindow()}")
                        }
                    }
                    is NexusAiPlan.EnterText -> {
                        val service = NexusAccessibilityService.current
                        if (!userConfirmed) NexusExecutionOutcome(false, "Нужно подтверждение ввода текста в активное поле.", requiresConfirmation = true)
                        else if (service == null) NexusExecutionOutcome(false, "Служба специальных возможностей NEXUS не подключена.")
                        else {
                            val ok = service.executeApprovedAction(NexusAction.EnterText(plan.text), true)
                            NexusExecutionOutcome(ok, if (ok) "Текст введён. Экран: ${service.describeActiveWindow()}" else "Не удалось ввести текст. Экран: ${service.describeActiveWindow()}")
                        }
                    }
                    is NexusAiPlan.Scroll -> {
                        val service = NexusAccessibilityService.current
                        if (service == null) NexusExecutionOutcome(false, "Служба специальных возможностей NEXUS не подключена.")
                        else {
                            val forward = plan.direction == "FORWARD"
                            val ok = service.executeApprovedAction(NexusAction.Scroll(forward), true)
                            NexusExecutionOutcome(ok, if (ok) "Экран прокручен. Текущие элементы: ${service.describeActiveWindow()}" else "На экране не найден подходящий элемент для прокрутки.")
                        }
                    }
                    is NexusAiPlan.Wait -> {
                        delay(plan.milliseconds.toLong())
                        NexusExecutionOutcome(true, "Подождал ${plan.milliseconds} мс. Экран: ${NexusAccessibilityService.current?.describeActiveWindow() ?: "служба специальных возможностей не подключена"}")
                    }
                    NexusAiPlan.GoBack -> {
                        val service = NexusAccessibilityService.current
                        if (service == null) NexusExecutionOutcome(false, "Служба специальных возможностей NEXUS не подключена.")
                        else {
                            val ok = service.executeApprovedAction(NexusAction.GoBack, true)
                            NexusExecutionOutcome(ok, if (ok) "Вернулся на предыдущий экран. Экран: ${service.describeActiveWindow()}" else "Android не выполнил действие «Назад».")
                        }
                    }
                    is NexusAiPlan.Answer, is NexusAiPlan.Done, is NexusAiPlan.Unsupported ->
                        NexusExecutionOutcome(false, "Ответ модели обрабатывается планировщиком.")
                }
            },
            maxSteps = 10,
            onEvent = onEvent
        )
    }

    private fun runAgentFallback(
        request: String,
        onEvent: (NexusAgentEvent) -> Unit,
        onResult: (NexusAgentResult) -> Unit
    ) {
        val engine = NativeLlamaEngine.getInstance(this)
        val savedModel = getSharedPreferences("nexus_settings", MODE_PRIVATE)
            .getString("model_file", null)?.let(::File)
        if (!engine.isModelLoaded() && (savedModel == null || !savedModel.isFile)) {
            onResult(NexusAgentResult.Failed("GGUF-модель ещё не подготовлена. Один раз выбери файл и нажми «Подготовить».", emptyList()))
            return
        }
        agentJob = lifecycleScope.launch {
            // A wake-word command can arrive while the startup auto-load is still running.
            // Wait for that single load instead of rejecting the user's command immediately.
            if (!engine.isModelLoaded()) {
                onEvent(NexusAgentEvent(NexusAgentEventType.THINKING, "Жду загрузку модели", "Команда сохранена; ожидаю завершения автоматической загрузки GGUF."))
                val deadline = System.nanoTime() + 180_000_000_000L
                while (!engine.isModelLoaded() && System.nanoTime() < deadline) {
                    delay(250)
                }
            }
            if (!engine.isModelLoaded()) {
                onResult(NexusAgentResult.Failed("Автоматическая загрузка GGUF не завершилась за 3 минуты. Проверь память устройства и журнал модели.", emptyList()))
                return@launch
            }
            val installedApps = InstalledAppLauncher.listLaunchableApps(this@MainActivity)
            val screen = NexusAccessibilityService.current?.describeActiveWindow()
                ?: "Служба специальных возможностей не подключена; доступен только запуск приложений."
            val context = listOf(
                "Installed launchable apps (use exact visible labels from this list): ${installedApps.joinToString(", ").take(1800)}",
                "Initial screen: ${screen.take(1200)}"
            )
            val result = try {
                createAgentLoop(onEvent).run(request, context)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                NexusAgentResult.Failed("Ошибка выполнения: ${error.message ?: error.javaClass.simpleName}", emptyList())
            }
            onResult(result)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent.getStringExtra(EXTRA_WAKE_WORD_COMMAND)?.takeIf { it.isNotBlank() }?.let { command ->
            sendBroadcast(Intent(NexusWakeWordService.ACTION_COMMAND).setPackage(packageName)
                .putExtra(NexusWakeWordService.EXTRA_COMMAND, command))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (intent?.getBooleanExtra(EXTRA_WAKE_WORD_OPEN, false) == true) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                setShowWhenLocked(true)
                setTurnScreenOn(true)
            } else {
                @Suppress("DEPRECATION")
                window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or android.view.WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
            }
        }
        // Draw edge-to-edge, but keep all interactive content inside Android's safe area.
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }
        textToSpeech = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                textToSpeech?.let { tts ->
                    val russian = Locale("ru", "RU")
                    val languageResult = tts.setLanguage(russian)
                    // Original JARVIS-inspired delivery: calm, lower-pitched,
                    // measured Russian speech. Uses the voice supplied by the
                    // device TTS engine; no actor/character voice clone.
                    tts.setPitch(0.82f)
                    tts.setSpeechRate(0.90f)
                }
            }
        }
        val prefs = getSharedPreferences("nexus_settings", MODE_PRIVATE)
        setContent {
            var modelName by remember { mutableStateOf(prefs.getString("model_name", null) ?: "Модель не выбрана") }
            var modelUri by remember { mutableStateOf(prefs.getString("model_uri", null)) }
            var modelFileStatus by remember {
                val saved = prefs.getString("model_file", null)?.let(::File)
                mutableStateOf(if (saved?.isFile == true) "Файл подготовлен: ${saved.name}" else "GGUF-файл ещё не подготовлен")
            }
            var engineStatus by remember { mutableStateOf("Модель не загружена в оперативную память") }
            var modelLoading by remember { mutableStateOf(false) }
            var modelProgress by remember { mutableStateOf(0f) }
            var command by remember { mutableStateOf("") }
            var busy by remember { mutableStateOf(false) }
            var listening by remember { mutableStateOf(false) }
            var wakeOverlay by remember { mutableStateOf(intent?.getBooleanExtra(EXTRA_WAKE_WORD_OPEN, false) == true) }
            var pendingWakeCommand by remember { mutableStateOf(intent?.getStringExtra(EXTRA_WAKE_WORD_COMMAND)) }
            var voiceStatus by remember { mutableStateOf("Голосовое управление готово") }
            var showModelSettings by remember { mutableStateOf(false) }
            var showUpdateSettings by remember { mutableStateOf(false) }
            var showDiagnostics by remember { mutableStateOf(false) }
            var diagnosticsText by remember { mutableStateOf("") }
            var pendingConfirmation by remember { mutableStateOf<NexusPendingConfirmation?>(NexusPendingActionStore.pending) }
            var chatLines by remember {
                mutableStateOf(listOf(ChatLine(1L, ChatRole.ASSISTANT, "NEXUS готов", "Я работаю на локальной модели. Напиши задачу — покажу план действий, выполнение каждого шага и итог.")))
            }
            val listState = rememberLazyListState()

            fun addLine(role: ChatRole, title: String, body: String = "") {
                chatLines = chatLines + ChatLine(System.nanoTime(), role, title, body)
            }

            fun showEvent(event: NexusAgentEvent) {
                when (event.type) {
                    NexusAgentEventType.STARTED -> addLine(ChatRole.ACTIVITY, event.title, event.detail)
                    NexusAgentEventType.THINKING -> addLine(ChatRole.ACTIVITY, event.title, event.detail)
                    NexusAgentEventType.PLAN -> addLine(ChatRole.ACTIVITY, "Шаг ${event.step}: ${event.title}", event.detail)
                    NexusAgentEventType.ACTION_STARTED -> addLine(ChatRole.ACTIVITY, "Шаг ${event.step}: ${event.title}", event.detail)
                    NexusAgentEventType.ACTION_RESULT -> addLine(ChatRole.ACTIVITY, if (event.title == "Шаг выполнен") "Шаг ${event.step}: успешно" else "Шаг ${event.step}: ошибка", event.detail)
                    NexusAgentEventType.RETRY -> addLine(ChatRole.ACTIVITY, event.title, event.detail)
                    NexusAgentEventType.WARNING -> addLine(ChatRole.ACTIVITY, event.title, event.detail)
                    NexusAgentEventType.FINISHED -> Unit
                }
            }

            fun finishAgent(result: NexusAgentResult) {
                busy = false
                val spokenReply = when (result) {
                    is NexusAgentResult.Completed -> result.summary.also { addLine(ChatRole.ASSISTANT, "Готово", it) }
                    is NexusAgentResult.Unsupported -> result.reason.also { addLine(ChatRole.ASSISTANT, "Не могу выполнить задачу", it) }
                    is NexusAgentResult.Failed -> result.reason.also { addLine(ChatRole.ASSISTANT, "Задача остановлена", it) }
                    is NexusAgentResult.AwaitingConfirmation -> {
                        pendingConfirmation = result.pending
                        result.explanation.also { addLine(ChatRole.ASSISTANT, "Требуется подтверждение", it) }
                    }
                }
                textToSpeech?.speak(spokenReply, TextToSpeech.QUEUE_FLUSH, null, "nexus-agent-reply")
            }

            fun submitRequest(rawRequest: String) {
                val request = rawRequest.trim()
                if (request.isEmpty() || busy) return
                command = ""
                addLine(ChatRole.USER, "Вы", request)
                busy = true

                val youtubeSearch = AppCommandParser.parseYouTubeSearch(request)
                if (youtubeSearch != null) {
                    val searchUri = Uri.parse("https://www.youtube.com/results").buildUpon()
                        .appendQueryParameter("search_query", youtubeSearch.query)
                        .build()
                    val youtubeIntent = Intent(Intent.ACTION_VIEW, searchUri).setPackage("com.google.android.youtube")
                    try {
                        startActivity(youtubeIntent)
                        busy = false
                        addLine(ChatRole.ASSISTANT, "Открываю YouTube", "Ищу: «${youtubeSearch.query}».")
                        textToSpeech?.speak("Ищу ${youtubeSearch.query} на YouTube.", TextToSpeech.QUEUE_FLUSH, null, "nexus-youtube-search")
                    } catch (_: android.content.ActivityNotFoundException) {
                        try {
                            startActivity(Intent(Intent.ACTION_VIEW, searchUri))
                            busy = false
                            addLine(ChatRole.ASSISTANT, "Открываю поиск YouTube", "Приложение YouTube не найдено; открыл поиск в браузере.")
                            textToSpeech?.speak("Открываю поиск YouTube в браузере.", TextToSpeech.QUEUE_FLUSH, null, "nexus-youtube-search")
                        } catch (error: Exception) {
                            busy = false
                            addLine(ChatRole.ASSISTANT, "Не удалось открыть YouTube", error.message ?: "Android не нашёл приложение или браузер.")
                        }
                    }
                } else {
                val parsed = AppCommandParser.parse(request)
                if (parsed != null) {
                    when (val launch = InstalledAppLauncher.launch(this@MainActivity, parsed.appName)) {
                        is InstalledAppLauncher.Result.Launched -> {
                            busy = false
                            addLine(ChatRole.ASSISTANT, "Приложение открыто", "Запустил «${launch.appLabel}».")
                        }
                        is InstalledAppLauncher.Result.NotFound -> {
                            addLine(ChatRole.ACTIVITY, "Не нашёл точное название приложения", "Передаю запрос локальной модели. Она получит список установленных приложений.")
                            runAgentFallback(request, ::showEvent, ::finishAgent)
                        }
                        is InstalledAppLauncher.Result.Failed -> {
                            busy = false
                            addLine(ChatRole.ASSISTANT, "Не удалось открыть приложение", launch.reason)
                        }
                    }
                } else {
                    runAgentFallback(request, ::showEvent, ::finishAgent)
                }
                }
            }


            LaunchedEffect(pendingWakeCommand) {
                val spokenCommand = pendingWakeCommand
                if (!spokenCommand.isNullOrBlank()) {
                    pendingWakeCommand = null
                    submitRequest(spokenCommand)
                }
            }

            DisposableEffect(Unit) {
                val receiver = object : android.content.BroadcastReceiver() {
                    override fun onReceive(context: android.content.Context?, intent: Intent?) {
                        when (intent?.action) {
                            NexusWakeWordService.ACTION_COMMAND -> {
                                val spoken = intent.getStringExtra(NexusWakeWordService.EXTRA_COMMAND).orEmpty()
                                if (spoken.isNotBlank()) submitRequest(spoken)
                            }
                            NexusWakeWordService.ACTION_STATUS -> {
                                voiceStatus = intent.getStringExtra(NexusWakeWordService.EXTRA_STATUS).orEmpty()
                                NexusDiagnostics.record(this@MainActivity, "VOICE", voiceStatus)
                            }
                        }
                    }
                }
                val filter = IntentFilter().apply {
                    addAction(NexusWakeWordService.ACTION_COMMAND)
                    addAction(NexusWakeWordService.ACTION_STATUS)
                }
                ContextCompat.registerReceiver(this@MainActivity, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
                onDispose { try { unregisterReceiver(receiver) } catch (_: Exception) {} }
            }

            val speechRecognizer = remember {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                    SpeechRecognizer.isOnDeviceRecognitionAvailable(this@MainActivity)) {
                    SpeechRecognizer.createOnDeviceSpeechRecognizer(this@MainActivity)
                } else {
                    SpeechRecognizer.createSpeechRecognizer(this@MainActivity)
                }
            }
            DisposableEffect(speechRecognizer) {
                speechRecognizer.setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) { listening = true; voiceStatus = "Слушаю команду…" }
                    override fun onBeginningOfSpeech() { voiceStatus = "Речь распознана, обрабатываю…" }
                    override fun onRmsChanged(rmsdB: Float) {}
                    override fun onBufferReceived(buffer: ByteArray?) {}
                    override fun onEndOfSpeech() { listening = false; voiceStatus = "Распознаю сказанное…" }
                    override fun onError(error: Int) {
                        listening = false
                        voiceStatus = when (error) {
                            SpeechRecognizer.ERROR_NO_MATCH -> "Не удалось разобрать речь. Попробуй ещё раз."
                            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Не услышал команду."
                            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Нет разрешения на микрофон."
                            SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT ->
                                "Системный распознаватель требует сеть. Проверь доступность офлайн-пакета русского языка."
                            else -> "Ошибка распознавания речи (код $error)."
                        }
                        addLine(ChatRole.ACTIVITY, "Голосовой ввод", voiceStatus)
                    }
                    override fun onResults(results: Bundle?) {
                        listening = false
                        val spoken = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                        if (spoken.isNotBlank()) {
                            voiceStatus = "Распознано: $spoken"
                            NexusDiagnostics.record(this@MainActivity, "VOICE", "Распознано: $spoken")
                            submitRequest(spoken)
                        } else {
                            voiceStatus = "Речь не распознана. Повтори команду."
                            NexusDiagnostics.record(this@MainActivity, "VOICE", voiceStatus)
                        }
                    }
                    override fun onPartialResults(partialResults: Bundle?) {
                        val partial = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                        if (!partial.isNullOrBlank()) voiceStatus = "Слышу: $partial"
                    }
                    override fun onEvent(eventType: Int, params: Bundle?) {}
                })
                onDispose { speechRecognizer.destroy() }
            }
            val microphonePermission = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestPermission()
            ) { granted ->
                if (granted) {
                    val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                        putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ru-RU")
                        putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "ru-RU")
                        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
                        putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                    }
                    try {
                        speechRecognizer.startListening(intent)
                        listening = true
                        voiceStatus = "Запускаю распознавание…"
                    } catch (error: Exception) {
                        listening = false
                        voiceStatus = "Не удалось запустить микрофон: ${error.message ?: error.javaClass.simpleName}"
                    }
                } else {
                    voiceStatus = "Для голосовых команд нужно разрешение на микрофон."
                }
            }

            // Automatically restore the previously imported GGUF on every app start.
            // The file remains in app-private storage; only the in-memory model is reloaded.
            LaunchedEffect(modelFileStatus) {
                val file = prefs.getString("model_file", null)?.let(::File)
                val engine = NativeLlamaEngine.getInstance(this@MainActivity)
                if (file != null && file.isFile && !engine.isModelLoaded() && !modelLoading) {
                    modelLoading = true
                    modelProgress = 0f
                    engineStatus = "Автоматически загружаю сохранённую модель в RAM…"
                    NexusDiagnostics.record(this@MainActivity, "MODEL", "Автозагрузка сохранённой GGUF: ${file.name}")
                    try {
                        val info = engine.loadModel(file) { progress ->
                            modelProgress = progress
                            engineStatus = "Автозагрузка модели: ${(progress * 100).toInt()}%"
                        }
                        engineStatus = "Модель автоматически загружена: $info"
                        NexusDiagnostics.record(this@MainActivity, "MODEL", "Автозагрузка завершена: $info")
                    } catch (error: Throwable) {
                        engineStatus = "Автозагрузка не удалась: ${error.message ?: error.javaClass.simpleName}. Можно повторить вручную."
                        NexusDiagnostics.record(this@MainActivity, "MODEL_ERROR", engineStatus)
                    } finally {
                        modelLoading = false
                    }
                } else if (engine.isModelLoaded()) {
                    engineStatus = "Сохранённая модель уже активна в памяти."
                } else if (file == null || !file.isFile) {
                    engineStatus = "Модель ещё не подготовлена. Выбери GGUF и нажми «Подготовить» один раз."
                }
            }

            LaunchedEffect(chatLines.size) {
                if (chatLines.isNotEmpty()) listState.animateScrollToItem(chatLines.lastIndex)
            }

            val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
                uri?.let {
                    try {
                        contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    } catch (_: Exception) { }
                    modelUri = it.toString()
                    modelName = contentResolver.query(it, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                        if (cursor.moveToFirst()) cursor.getString(0) else null
                    } ?: "GGUF-модель выбрана"
                    prefs.edit().putString("model_uri", it.toString()).putString("model_name", modelName).apply()
                    modelFileStatus = "Файл выбран. Нажми «Подготовить модель»."
                    showModelSettings = true
                }
            }

            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = androidx.compose.ui.graphics.Color(0xFF70DFFF),
                    onPrimary = androidx.compose.ui.graphics.Color(0xFF06121D),
                    primaryContainer = androidx.compose.ui.graphics.Color(0xFF15394F),
                    secondary = androidx.compose.ui.graphics.Color(0xFFB69AFF),
                    secondaryContainer = androidx.compose.ui.graphics.Color(0xFF30254B),
                    background = androidx.compose.ui.graphics.Color(0xFF070B14),
                    surface = androidx.compose.ui.graphics.Color(0xFF0D1523),
                    surfaceVariant = androidx.compose.ui.graphics.Color(0xFF1A2638),
                    surfaceContainerLow = androidx.compose.ui.graphics.Color(0xFF111D2D),
                    surfaceContainerHighest = androidx.compose.ui.graphics.Color(0xFF24334A),
                    onBackground = androidx.compose.ui.graphics.Color(0xFFEAF2FF),
                    onSurface = androidx.compose.ui.graphics.Color(0xFFEAF2FF),
                    onSurfaceVariant = androidx.compose.ui.graphics.Color(0xFFA3B2C8)
                )
            ) {
                Surface(Modifier.fillMaxSize(), color = androidx.compose.ui.graphics.Color(0xFF070B14)) {
                    Column(
                        Modifier
                            .fillMaxSize()
                            .background(androidx.compose.ui.graphics.Color(0xFF070B14))
                            .windowInsetsPadding(WindowInsets.safeDrawing)
                            .background(MaterialTheme.colorScheme.background)
                            .padding(horizontal = 12.dp, vertical = 8.dp)
                    ) {
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column {
                                Text("NEXUS", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                                Text("Локальный AI-помощник", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Surface(
                                        shape = RoundedCornerShape(50),
                                        color = if (NativeLlamaEngine.getInstance(this@MainActivity).isModelLoaded()) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
                                    ) { Text(if (NativeLlamaEngine.getInstance(this@MainActivity).isModelLoaded()) "Модель активна" else "Офлайн", Modifier.padding(horizontal = 10.dp, vertical = 5.dp), style = MaterialTheme.typography.labelSmall) }
                                }
                                TextButton(onClick = { showModelSettings = !showModelSettings }) {
                                    Text(if (showModelSettings) "Скрыть настройки" else "Модель и доступ")
                                }
                                TextButton(onClick = { showUpdateSettings = !showUpdateSettings }) {
                                    Text(if (showUpdateSettings) "Скрыть обновление" else "Обновить NEXUS")
                                }
                                TextButton(onClick = {
                                    diagnosticsText = NexusDiagnostics.read(this@MainActivity)
                                    showDiagnostics = true
                                }) { Text("Логи") }
                            }
                        }

                        NexusVoiceOrb(active = listening || busy || wakeOverlay)

                        if (showUpdateSettings) {
                            NexusUpdatePanel()
                            Spacer(Modifier.height(6.dp))
                        }

                        if (showModelSettings) {
                            Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                                    Text("Локальная модель", style = MaterialTheme.typography.titleMedium)
                                    Text(modelName, style = MaterialTheme.typography.bodySmall)
                                    Text(modelFileStatus, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        OutlinedButton(onClick = { picker.launch(arrayOf("*/*")) }) { Text("Выбрать GGUF") }
                                        if (modelUri != null) {
                                            Button(onClick = {
                                                lifecycleScope.launch {
                                                    try {
                                                        modelFileStatus = "Импортирую GGUF…"
                                                        val imported = ModelStorage.importModel(this@MainActivity, Uri.parse(modelUri.orEmpty()))
                                                        prefs.edit().putString("model_file", imported.absolutePath).apply()
                                                        modelFileStatus = "Подготовлено: ${imported.name}"
                                                    } catch (error: Exception) {
                                                        modelFileStatus = "Ошибка импорта: ${error.message ?: "неизвестная ошибка"}"
                                                    }
                                                }
                                            }) { Text("Подготовить") }
                                        }
                                    }
                                    Text(engineStatus, style = MaterialTheme.typography.bodySmall)
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Button(
                                            enabled = !modelLoading,
                                            onClick = {
                                                lifecycleScope.launch {
                                                    val file = prefs.getString("model_file", null)?.let(::File)
                                                    if (file == null || !file.isFile) {
                                                        engineStatus = "Сначала выбери GGUF и нажми «Подготовить»."
                                                    } else {
                                                        modelLoading = true
                                                        modelProgress = 0f
                                                        engineStatus = "Загружаю модель в RAM…"
                                                        try {
                                                            val info = NativeLlamaEngine.getInstance(this@MainActivity).loadModel(file) { progress ->
                                                                modelProgress = progress
                                                                engineStatus = "Загрузка модели: ${(progress * 100).toInt()}%"
                                                            }
                                                            engineStatus = "Загружена: $info"
                                                        } catch (error: Throwable) {
                                                            engineStatus = if (error.message?.contains("отменена", true) == true)
                                                                "Загрузка модели отменена."
                                                            else "Ошибка загрузки: ${error.message ?: error.javaClass.simpleName}"
                                                        } finally {
                                                            modelLoading = false
                                                        }
                                                    }
                                                }
                                            }
                                        ) { Text(if (modelLoading) "Загрузка…" else "Загрузить модель") }
                                        OutlinedButton(onClick = {
                                            lifecycleScope.launch {
                                                try {
                                                    NativeLlamaEngine.getInstance(this@MainActivity).cancelAndUnload()
                                                    engineStatus = "Модель выгружена. Оперативная память освобождена."
                                                    modelLoading = false
                                                    busy = false
                                                } catch (error: Exception) {
                                                    engineStatus = "Ошибка выгрузки: ${error.message}"
                                                }
                                            }
                                        }) { Text("Выгрузить") }
                                    }
                                    if (modelLoading) {
                                        Text("Загрузка GGUF: ${(modelProgress * 100).toInt()}%")
                                        OutlinedButton(onClick = {
                                            lifecycleScope.launch {
                                                engineStatus = "Останавливаю загрузку…"
                                                agentJob?.cancel()
                                                agentJob = null
                                                try {
                                                    NativeLlamaEngine.getInstance(this@MainActivity).cancelAndUnload()
                                                    engineStatus = "Загрузка экстренно остановлена."
                                                } catch (error: Exception) {
                                                    engineStatus = "Ошибка остановки: ${error.message}"
                                                } finally {
                                                    modelLoading = false
                                                    busy = false
                                                }
                                            }
                                        }) { Text("Экстренно остановить загрузку") }
                                    }
                                    OutlinedButton(onClick = {
                                        busy = false
                                        pendingConfirmation = null
                                        agentJob?.cancel()
                                        agentJob = null
                                        lifecycleScope.launch {
                                            engineStatus = "Экстренно выключаю модель…"
                                            try {
                                                NativeLlamaEngine.getInstance(this@MainActivity).cancelAndUnload()
                                                engineStatus = "NEXUS остановлен. Модель выгружена из RAM."
                                            } catch (error: Exception) {
                                                engineStatus = "Ошибка аварийной остановки: ${error.message}"
                                            } finally {
                                                modelLoading = false
                                            }
                                        }
                                    }) { Text("ЭКСТРЕННО ВЫКЛЮЧИТЬ NEXUS") }
                                    OutlinedButton(onClick = { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }) {
                                        Text("Настройки специальных возможностей")
                                    }
                                    OutlinedButton(onClick = {
                                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this@MainActivity)) {
                                            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
                                        } else {
                                            Toast.makeText(this@MainActivity, "Плавающий шар NEXUS уже разрешён.", Toast.LENGTH_SHORT).show()
                                        }
                                    }) {
                                        Text(if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && Settings.canDrawOverlays(this@MainActivity)) "Плавающий шар разрешён" else "Разрешить шар поверх приложений")
                                    }
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Button(onClick = {
                                            if (ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                                                val serviceIntent = Intent(this@MainActivity, NexusWakeWordService::class.java).setAction(NexusWakeWordService.ACTION_START)
                                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(serviceIntent) else startService(serviceIntent)
                                                voiceStatus = "Фоновое ожидание включено. Скажи «Нексус»."
                                            } else microphonePermission.launch(Manifest.permission.RECORD_AUDIO)
                                        }) { Text("Включить «Нексус»") }
                                        OutlinedButton(onClick = {
                                            startService(Intent(this@MainActivity, NexusWakeWordService::class.java).setAction(NexusWakeWordService.ACTION_STOP))
                                            voiceStatus = "Голосовое ожидание остановлено."
                                        }) { Text("Выключить") }
                                    }
                                }
                            }
                            Spacer(Modifier.height(6.dp))
                        }

                        LazyColumn(
                            modifier = Modifier.weight(1f).fillMaxWidth(),
                            state = listState,
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            items(chatLines, key = { it.id }) { line ->
                                NexusChatBubble(line)
                            }
                            if (busy) {
                                item(key = "nexus-working") {
                                    Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                                        Text("NEXUS выполняет задачу…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                            }
                        }

                        Spacer(Modifier.height(8.dp))
                        Text(voiceStatus, Modifier.fillMaxWidth().padding(bottom = 5.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                onClick = {
                                    if (listening) {
                                        speechRecognizer.stopListening()
                                        listening = false
                                        voiceStatus = "Распознавание остановлено."
                                    } else if (ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                                        microphonePermission.launch(Manifest.permission.RECORD_AUDIO)
                                    } else {
                                        microphonePermission.launch(Manifest.permission.RECORD_AUDIO)
                                    }
                                },
                                enabled = !busy,
                                modifier = Modifier.height(56.dp)
                            ) { Text(if (listening) "Стоп" else "🎙") }
                            OutlinedTextField(
                                value = command,
                                onValueChange = { command = it },
                                modifier = Modifier.weight(1f),
                                placeholder = { Text("Скажи команду или напиши…") },
                                maxLines = 4,
                                enabled = !busy
                            )
                            Button(
                                onClick = { submitRequest(command) },
                                enabled = command.isNotBlank() && !busy,
                                modifier = Modifier.height(56.dp)
                            ) { Text("Отправить") }
                        }
                        Text(
                            "Запросы обрабатываются локальной GGUF-моделью. Нажатия и ввод текста требуют подтверждения.",
                            Modifier.fillMaxWidth().padding(top = 5.dp, bottom = 2.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            if (showDiagnostics) {
                AlertDialog(
                    onDismissRequest = { showDiagnostics = false },
                    title = { Text("Диагностика NEXUS") },
                    text = {
                        Column(
                            Modifier.fillMaxWidth().heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text("Последние действия и ошибки хранятся только на этом устройстве.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(diagnosticsText, style = MaterialTheme.typography.bodySmall)
                            TextButton(onClick = {
                                val report = NexusDiagnostics.analyze(this@MainActivity)
                                diagnosticsText = report
                                val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                                clipboard.setPrimaryClip(ClipData.newPlainText("NEXUS diagnostics", report))
                                Toast.makeText(this@MainActivity, "Анализ скопирован", Toast.LENGTH_SHORT).show()
                            }) { Text("Анализировать и скопировать") }
                        }
                    },
                    confirmButton = {
                        TextButton(onClick = {
                            val report = NexusDiagnostics.read(this@MainActivity)
                            val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.setPrimaryClip(ClipData.newPlainText("NEXUS logs", report))
                            Toast.makeText(this@MainActivity, "Журнал скопирован", Toast.LENGTH_SHORT).show()
                        }) { Text("Копировать журнал") }
                    },
                    dismissButton = {
                        Row {
                            TextButton(onClick = {
                                NexusDiagnostics.clear(this@MainActivity)
                                diagnosticsText = NexusDiagnostics.read(this@MainActivity)
                            }) { Text("Очистить") }
                            TextButton(onClick = { showDiagnostics = false }) { Text("Закрыть") }
                        }
                    }
                )
            }

            pendingConfirmation?.let { pending ->
                AlertDialog(
                    onDismissRequest = { pendingConfirmation = null },
                    title = { Text("Подтвердить действие?") },
                    text = { Text("NEXUS предлагает: ${pending.plan}. Действие будет выполнено только после подтверждения.") },
                    confirmButton = {
                        TextButton(onClick = {
                            pendingConfirmation = null
                            NexusPendingActionStore.pending = null
                            busy = true
                            lifecycleScope.launch {
                                val result = try {
                                    createAgentLoop(::showEvent).resume(pending)
                                } catch (error: Exception) {
                                    NexusAgentResult.Failed("Ошибка продолжения: ${error.message ?: error.javaClass.simpleName}", emptyList())
                                }
                                finishAgent(result)
                            }
                        }) { Text("Подтвердить") }
                    },
                    dismissButton = {
                        TextButton(onClick = {
                            pendingConfirmation = null
                            NexusPendingActionStore.pending = null
                            busy = false
                            addLine(ChatRole.ASSISTANT, "Действие отменено", "Я не выполнял это действие.")
                        }) { Text("Отмена") }
                    }
                )
            }
        }
    }
    override fun onDestroy() {
        textToSpeech?.stop()
        textToSpeech?.shutdown()
        textToSpeech = null
        super.onDestroy()
    }
}

@androidx.compose.runtime.Composable
private fun NexusChatBubble(line: ChatLine) {
    val isUser = line.role == ChatRole.USER
    val isActivity = line.role == ChatRole.ACTIVITY
    val alignment = if (isUser) Alignment.End else Alignment.Start
    val container = when {
        isUser -> MaterialTheme.colorScheme.primaryContainer
        isActivity -> MaterialTheme.colorScheme.surfaceContainerLow
        else -> MaterialTheme.colorScheme.secondaryContainer
    }
    Column(Modifier.fillMaxWidth(), horizontalAlignment = alignment) {
        Surface(
            modifier = Modifier.fillMaxWidth(if (isActivity) 0.96f else 0.88f),
            color = container,
            shape = RoundedCornerShape(
                topStart = 18.dp,
                topEnd = 18.dp,
                bottomEnd = if (isUser) 5.dp else 18.dp,
                bottomStart = if (isUser) 18.dp else 5.dp
            )
        ) {
            Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    line.title,
                    style = if (isActivity) MaterialTheme.typography.labelMedium else MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                if (line.body.isNotBlank()) {
                    Text(
                        line.body,
                        style = if (isActivity) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}


@androidx.compose.runtime.Composable
private fun NexusVoiceOrb(active: Boolean) {
    val transition = rememberInfiniteTransition(label = "nexus-voice-orb")
    val pulse by transition.animateFloat(
        initialValue = 0.88f,
        targetValue = if (active) 1.12f else 1.035f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1050),
            repeatMode = RepeatMode.Reverse
        ),
        label = "orb-pulse"
    )
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = (2f * PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2200),
            repeatMode = RepeatMode.Restart
        ),
        label = "orb-wave"
    )

    Canvas(Modifier.fillMaxWidth().height(104.dp)) {
        val center = Offset(size.width / 2f, size.height / 2f)
        val radius = 29.dp.toPx()
        val glowRadius = radius * pulse * 1.85f
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    androidx.compose.ui.graphics.Color(0x6657D7FF),
                    androidx.compose.ui.graphics.Color(0x332D8BFF),
                    androidx.compose.ui.graphics.Color.Transparent
                ),
                center = center,
                radius = glowRadius
            ),
            radius = glowRadius,
            center = center
        )
        drawCircle(
            color = androidx.compose.ui.graphics.Color(0x9957D7FF),
            radius = radius * pulse * 1.28f,
            center = center,
            style = Stroke(width = 1.5.dp.toPx())
        )
        drawCircle(
            brush = Brush.linearGradient(
                colors = listOf(
                    androidx.compose.ui.graphics.Color(0xFF63E6FF),
                    androidx.compose.ui.graphics.Color(0xFF657BFF),
                    androidx.compose.ui.graphics.Color(0xFFB56CFF)
                ),
                start = Offset(center.x - radius, center.y - radius),
                end = Offset(center.x + radius, center.y + radius)
            ),
            radius = radius * pulse,
            center = center
        )
        drawCircle(
            color = androidx.compose.ui.graphics.Color(0x99FFFFFF),
            radius = radius * 0.52f,
            center = Offset(center.x - radius * 0.20f, center.y - radius * 0.22f)
        )

        val barCount = 31
        val barWidth = 2.2.dp.toPx()
        val maxHeight = 13.dp.toPx()
        val baseY = center.y + radius + 14.dp.toPx()
        for (i in 0 until barCount) {
            val normalized = i.toFloat() / (barCount - 1)
            val envelope = sin(normalized * PI).toFloat()
            val oscillation = (0.25f + 0.75f * kotlin.math.abs(
                sin(phase + i * 0.48f)
            ))
            val barHeight = (3.dp.toPx() + maxHeight * envelope * oscillation).coerceAtLeast(2.dp.toPx())
            val x = center.x - (barCount - 1) * barWidth * 1.45f / 2f + i * barWidth * 1.45f
            drawLine(
                color = androidx.compose.ui.graphics.Color(0xFF83DFFF).copy(alpha = 0.45f + 0.5f * envelope),
                start = Offset(x, baseY - barHeight / 2f),
                end = Offset(x, baseY + barHeight / 2f),
                strokeWidth = barWidth,
                cap = StrokeCap.Round
            )
        }
    }
}
