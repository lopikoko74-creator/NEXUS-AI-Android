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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("nexus_settings", MODE_PRIVATE)
        setContent {
            var modelName by remember { mutableStateOf(prefs.getString("model_name", null) ?: "Модель не выбрана") }
            var modelUri by remember { mutableStateOf(prefs.getString("model_uri", null)) }
            var modelStatus by remember { mutableStateOf("Модель ещё не импортирована в хранилище приложения") }
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
                        Text("NEXUS AI", style = MaterialTheme.typography.headlineLarge)
                        Text("Локальный помощник для Android")
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
                                                val imported = ModelStorage.importModel(this@MainActivity, Uri.parse(modelUri))
                                                prefs.edit().putString("model_file", imported.absolutePath).apply()
                                                modelStatus = "Импорт завершён: ${imported.name}. Движок инференса подключается следующим этапом."
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
                                Text("Экран приложения, выбор GGUF-файла и регистрация AccessibilityService добавлены.")
                                Text("Инференс llama.cpp, wake-word «NEXUS», распознавание речи и голосовое подтверждение ещё не реализованы.")
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