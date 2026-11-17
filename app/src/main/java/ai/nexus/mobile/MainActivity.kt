package ai.nexus.mobile

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
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

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("nexus_settings", MODE_PRIVATE)
        setContent {
            var modelName by remember { mutableStateOf(prefs.getString("model_name", null) ?: "Модель не выбрана") }
            val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
                uri?.let {
                    contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    modelName = it.lastPathSegment?.substringAfterLast('/') ?: "GGUF-модель выбрана"
                    prefs.edit().putString("model_uri", it.toString()).putString("model_name", modelName).apply()
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
                                Text("Файл выбирается через системный проводник Android. Доступ к нему сохраняется; запуск модели пока не подключён.")
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