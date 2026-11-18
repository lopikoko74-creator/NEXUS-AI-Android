package ai.nexus.mobile

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.TextButton
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import java.io.File

class MainActivity : ComponentActivity() {
    private fun createAgentLoop(): NexusAgentLoop {
        val engine = NativeLlamaEngine.getInstance(this)
        return NexusAgentLoop(
            generate = { prompt -> engine.generate(prompt, 384) },
            execute = { plan, userConfirmed ->
                when (plan) {
                    is NexusAiPlan.OpenApp -> when (val result = InstalledAppLauncher.launch(this, plan.appName)) {
                        is InstalledAppLauncher.Result.Launched ->
                            NexusExecutionOutcome(true, "Opened installed app: ${result.appLabel}. Current screen: ${NexusAccessibilityService.current?.describeActiveWindow() ?: "Accessibility service is not connected."}")
                        is InstalledAppLauncher.Result.NotFound ->
                            NexusExecutionOutcome(false, "App is not installed or was not found: ${result.requestedName}")
                        is InstalledAppLauncher.Result.Failed ->
                            NexusExecutionOutcome(false, "Could not open app: ${result.reason}")
                    }
                    is NexusAiPlan.TapText -> {
                        val service = NexusAccessibilityService.current
                        if (!userConfirmed) NexusExecutionOutcome(false, "NEXUS wants to tap the exact visible label '${plan.exactLabel}'.", requiresConfirmation = true)
                        else if (service == null) NexusExecutionOutcome(false, "Accessibility service is not enabled.")
                        else {
                            val ok = service.executeApprovedAction(NexusAction.TapVisibleText(plan.exactLabel), true)
                            NexusExecutionOutcome(ok, if (ok) "Tapped '${plan.exactLabel}'. Screen: ${service.describeActiveWindow()}" else "Could not tap exact visible label '${plan.exactLabel}'. Screen: ${service.describeActiveWindow()}")
                        }
                    }
                    is NexusAiPlan.EnterText -> {
                        val service = NexusAccessibilityService.current
                        if (!userConfirmed) NexusExecutionOutcome(false, "NEXUS wants to enter text into the currently focused field.", requiresConfirmation = true)
                        else if (service == null) NexusExecutionOutcome(false, "Accessibility service is not enabled.")
                        else {
                            val ok = service.executeApprovedAction(NexusAction.EnterText(plan.text), true)
                            NexusExecutionOutcome(ok, if (ok) "Text entered. Screen: ${service.describeActiveWindow()}" else "Text entry failed. Screen: ${service.describeActiveWindow()}")
                        }
                    }
                    is NexusAiPlan.Scroll -> {
                        val service = NexusAccessibilityService.current
                        if (service == null) NexusExecutionOutcome(false, "Accessibility service is not enabled.")
                        else {
                            val forward = plan.direction == "FORWARD"
                            val ok = service.executeApprovedAction(NexusAction.Scroll(forward), true)
                            NexusExecutionOutcome(ok, if (ok) "Scrolled ${plan.direction.lowercase()}. Screen: ${service.describeActiveWindow()}" else "Scroll action failed.")
                        }
                    }
                    is NexusAiPlan.Wait -> {
                        delay(plan.milliseconds.toLong())
                        NexusExecutionOutcome(true, "Waited ${plan.milliseconds} ms. Screen: ${NexusAccessibilityService.current?.describeActiveWindow() ?: "Accessibility service is not connected."}")
                    }
                    NexusAiPlan.GoBack -> {
                        val service = NexusAccessibilityService.current
                        if (service == null) NexusExecutionOutcome(false, "Accessibility service is not enabled.")
                        else {
                            val ok = service.executeApprovedAction(NexusAction.GoBack, true)
                            NexusExecutionOutcome(ok, if (ok) "Navigated back. Screen: ${service.describeActiveWindow()}" else "Android back action failed.")
                        }
                    }
                    is NexusAiPlan.Answer, is NexusAiPlan.Done, is NexusAiPlan.Unsupported ->
                        NexusExecutionOutcome(false, "Terminal model response is handled by the agent loop.")
                }
            },
            maxSteps = 6
        )
    }

    private fun runAgentFallback(request: String, onResult: (NexusAgentResult) -> Unit) {
        val engine = NativeLlamaEngine.getInstance(this)
        if (!engine.isModelLoaded()) {
            onResult(NexusAgentResult.Failed("Неизвестная команда. Сначала загрузи локальную GGUF-модель.", emptyList()))
            return
        }
        val screen = NexusAccessibilityService.current?.describeActiveWindow() ?: "Accessibility service is not connected."
        lifecycleScope.launch { onResult(createAgentLoop().run(request, listOf("Initial screen: $screen"))) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("nexus_settings", MODE_PRIVATE)
        setContent {
            var modelName by remember { mutableStateOf(prefs.getString("model_name", null) ?: "Модель не выбрана") }
            var modelUri by remember { mutableStateOf(prefs.getString("model_uri", null)) }
            var engineStatus by remember { mutableStateOf("Нативный движок ещё не запускался") }
            var appCommand by remember { mutableStateOf("") }
            var appCommandStatus by remember { mutableStateOf("Команды открытия приложений пока принимаются текстом") }
            var pendingConfirmation by remember { mutableStateOf<NexusPendingConfirmation?>(null) }
            var modelStatus by remember {
                val savedPath = prefs.getString("model_file", null)
                val savedFile = savedPath?.let(::File)
                mutableStateOf(
                    if (savedFile?.isFile == true) "GGUF импортирован: ${savedFile.name}. Можно вручную загрузить в llama.cpp."
                    else "Модель ещё не импортирована в хранилище приложения"
                )
            }
            val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
                uri?.let {
                    contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    modelUri = it.toString()
                    modelName = contentResolver.query(it, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                        if (cursor.moveToFirst()) cursor.getString(0) else null
                    } ?: "GGUF-модель выбрана"
                    prefs.edit().putString("model_uri", it.toString()).putString("model_name", modelName).apply()
                    modelStatus = "Файл выбран. Следующий шаг — импорт в память приложения."
                }
            }
            MaterialTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    Column(
                        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        pendingConfirmation?.let { pending ->
                            AlertDialog(
                                onDismissRequest = { pendingConfirmation = null },
                                title = { Text("Подтвердить действие NEXUS?") },
                                text = { Text("Модель предлагает: ${pending.plan}. Это действие будет выполнено только после твоего подтверждения.") },
                                confirmButton = {
                                    TextButton(onClick = {
                                        pendingConfirmation = null
                                        appCommandStatus = "Выполняю подтверждённый шаг и проверяю результат…"
                                        this@MainActivity.lifecycleScope.launch {
                                            when (val result = createAgentLoop().resume(pending)) {
                                                is NexusAgentResult.Completed -> appCommandStatus = result.summary
                                                is NexusAgentResult.Unsupported -> appCommandStatus = "Задача остановлена: ${result.reason}"
                                                is NexusAgentResult.Failed -> appCommandStatus = "Цикл агента остановлен: ${result.reason}"
                                                is NexusAgentResult.AwaitingConfirmation -> {
                                                    pendingConfirmation = result.pending
                                                    appCommandStatus = result.explanation
                                                }
                                            }
                                        }
                                    }) { Text("Подтвердить") }
                                },
                                dismissButton = {
                                    TextButton(onClick = {
                                        pendingConfirmation = null
                                        appCommandStatus = "Действие отменено пользователем."
                                    }) { Text("Отмена") }
                                }
                            )
                        }
                        Text("NEXUS AI", style = MaterialTheme.typography.headlineLarge)
                        Text("Локальный помощник для Android")
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("Команды приложений", style = MaterialTheme.typography.titleMedium)
                                Text("Пока голосовое распознавание не подключено, здесь можно проверить разбор команд. Например: «Открой телегу», «Запусти ватсап», «Перейди в ютуб».")
                                OutlinedTextField(
                                    value = appCommand,
                                    onValueChange = { appCommand = it },
                                    label = { Text("Команда NEXUS") },
                                    modifier = Modifier.fillMaxWidth(),
                                    singleLine = true
                                )
                                Button(onClick = {
                                    val parsed = AppCommandParser.parse(appCommand)
                                    if (parsed == null) {
                                        appCommandStatus = "NEXUS передаёт неизвестную команду локальной модели…"
                                        runAgentFallback(appCommand) { result ->
                                            when (result) {
                                                is NexusAgentResult.Completed -> appCommandStatus = result.summary
                                                is NexusAgentResult.Unsupported -> appCommandStatus = "Модель не смогла безопасно выполнить задачу: ${result.reason}"
                                                is NexusAgentResult.Failed -> appCommandStatus = "Цикл агента остановлен: ${result.reason}"
                                                is NexusAgentResult.AwaitingConfirmation -> {
                                                    pendingConfirmation = result.pending
                                                    appCommandStatus = result.explanation
                                                }
                                            }
                                        }
                                    } else {
                                        when (val result = InstalledAppLauncher.launch(this@MainActivity, parsed.appName)) {
                                            is InstalledAppLauncher.Result.Launched ->
                                                appCommandStatus = "Запускаю: ${result.appLabel}"
                                            is InstalledAppLauncher.Result.NotFound -> {
                                                appCommandStatus = "Приложение не найдено. Передаю полную команду локальной модели…"
                                                runAgentFallback(appCommand) { agentResult ->
                                                    when (agentResult) {
                                                        is NexusAgentResult.Completed -> appCommandStatus = agentResult.summary
                                                        is NexusAgentResult.Unsupported -> appCommandStatus = "Модель не смогла безопасно выполнить задачу: ${agentResult.reason}"
                                                        is NexusAgentResult.Failed -> appCommandStatus = "Цикл агента остановлен: ${agentResult.reason}"
                                                        is NexusAgentResult.AwaitingConfirmation -> {
                                                            pendingConfirmation = agentResult.pending
                                                            appCommandStatus = agentResult.explanation
                                                        }
                                                    }
                                                }
                                            }
                                            is InstalledAppLauncher.Result.Failed ->
                                                appCommandStatus = "Не удалось запустить: ${result.reason}"
                                        }
                                    }
                                }, modifier = Modifier.fillMaxWidth()) { Text("Выполнить команду") }
                                Text(appCommandStatus)
                            }
                        }
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("Локальная модель", style = MaterialTheme.typography.titleMedium)
                                Text(modelName)
                                Button(onClick = { picker.launch(arrayOf("*/*")) }) { Text("Выбрать GGUF-файл") }
                                Text("Файл выбирается через системный проводник Android. Перед запуском модель копируется в приватное хранилище приложения и проверяется по заголовку GGUF.")
                                Text(modelStatus)
                                if (modelUri != null) {
                                    OutlinedButton(onClick = {
                                        this@MainActivity.lifecycleScope.launch {
                                            modelStatus = "Импорт модели…"
                                            try {
                                                val imported = ModelStorage.importModel(this@MainActivity, Uri.parse(modelUri.orEmpty()))
                                                prefs.edit().putString("model_file", imported.absolutePath).apply()
                                                modelStatus = "Импорт завершён: ${imported.name}. llama.cpp готов к ручной загрузке."
                                            } catch (error: Exception) {
                                                modelStatus = "Ошибка импорта: ${error.message ?: "неизвестная ошибка"}"
                                            }
                                        }
                                    }) { Text("Подготовить модель") }
                                }
                            }
                        }
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("Локальный ИИ — llama.cpp", style = MaterialTheme.typography.titleMedium)
                                Text("Загрузка модели выполняется только после нажатия. Для POCO F6 начни с GGUF 2–4B в квантизации Q4_K_M. Во время загрузки возможен высокий расход RAM и нагрев.")
                                Text(engineStatus)
                                Button(onClick = {
                                    this@MainActivity.lifecycleScope.launch {
                                        val path = prefs.getString("model_file", null)
                                        val file = path?.let(::File)
                                        if (file == null || !file.isFile) {
                                            engineStatus = "Сначала выбери и подготовь GGUF-файл."
                                        } else {
                                            engineStatus = "Инициализация llama.cpp и загрузка модели…"
                                            try {
                                                val info = NativeLlamaEngine.getInstance(this@MainActivity).loadModel(file)
                                                engineStatus = "Модель загружена: $info"
                                            } catch (error: LinkageError) {
                                                engineStatus = "Ошибка нативного движка: ${error.message ?: "библиотека не загрузилась"}"
                                            } catch (error: Exception) {
                                                engineStatus = "Не удалось загрузить модель: ${error.message ?: "неизвестная ошибка"}"
                                            }
                                        }
                                    }
                                }) { Text("Загрузить модель в llama.cpp") }
                                OutlinedButton(onClick = {
                                    this@MainActivity.lifecycleScope.launch {
                                        try {
                                            NativeLlamaEngine.getInstance(this@MainActivity).unloadModel()
                                            engineStatus = "Модель выгружена из памяти."
                                        } catch (error: Exception) {
                                            engineStatus = "Ошибка выгрузки: ${error.message ?: "неизвестная ошибка"}"
                                        }
                                    }
                                }) { Text("Выгрузить модель из RAM") }
                            }
                        }
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("Управление телефоном", style = MaterialTheme.typography.titleMedium)
                                Text("Чтобы NEXUS мог управлять интерфейсом, включи его службу специальных возможностей Android.")
                                Button(onClick = { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }) {
                                    Text("Открыть настройки Accessibility")
                                }
                            }
                        }
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("Текущий этап", style = MaterialTheme.typography.titleMedium)
                                Text("Подключены офлайн-генерация токенов llama.cpp, chat template выбранной GGUF-модели, JSON-планировщик и итеративный цикл: план → действие → наблюдение → перепланирование.")
                                Text("Голосовой wake-word, ASR и TTS пока отдельный следующий этап. Нажатия и ввод текста требуют подтверждения.")
                            }
                        }
                        Spacer(Modifier.height(4.dp))
                        OutlinedButton(onClick = { finish() }, modifier = Modifier.fillMaxWidth()) {
                            Text("Выключить NEXUS")
                        }
                    }
                }
            }
        }
    }
}