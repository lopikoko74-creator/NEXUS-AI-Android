package ai.nexus.mobile

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

private const val NEXUS_RELEASES_API =
    "https://api.github.com/repos/lopikoko74-creator/NEXUS-AI-Android/releases/latest"

@Composable
fun NexusUpdatePanel() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf("Проверяй обновления NEXUS AI через GitHub Releases.") }
    var busy by remember { mutableStateOf(false) }
    var downloadedFile by remember { mutableStateOf<File?>(null) }

    fun installDownloadedApk(file: File) {
        if (Build.VERSION.SDK_INT >= 26 && !context.packageManager.canRequestPackageInstalls()) {
            status = "Разреши NEXUS AI устанавливать неизвестные приложения, затем нажми «Установить» ещё раз."
            context.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${context.packageName}")))
            return
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        status = "APK передан системному установщику Android. Подтверди обновление в его окне."
    }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Обновление NEXUS AI", style = MaterialTheme.typography.titleMedium)
            Text(status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (busy) CircularProgressIndicator()
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(enabled = !busy, onClick = {
                    scope.launch {
                        busy = true
                        status = "Проверяю последний публичный релиз…"
                        downloadedFile = null
                        try {
                            val result = withContext(Dispatchers.IO) {
                                val connection = (URL(NEXUS_RELEASES_API).openConnection() as HttpURLConnection).apply {
                                    connectTimeout = 15000
                                    readTimeout = 30000
                                    setRequestProperty("Accept", "application/vnd.github+json")
                                    setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
                                    setRequestProperty("User-Agent", "NEXUS-AI-Updater")
                                }
                                val release = connection.inputStream.bufferedReader().use { JSONObject(it.readText()) }
                                connection.disconnect()
                                if (release.optBoolean("draft") || release.optBoolean("prerelease"))
                                    error("Последний релиз помечен как draft или prerelease.")
                                val assets = release.getJSONArray("assets")
                                val apk = (0 until assets.length()).map { assets.getJSONObject(it) }
                                    .firstOrNull { it.optString("name").endsWith(".apk", true) }
                                    ?: error("В последнем релизе не найден APK.")
                                val tag = release.optString("tag_name")
                                val current = context.packageManager.getPackageInfo(context.packageName, 0)
                                val currentVersion = if (Build.VERSION.SDK_INT >= 28) current.longVersionCode else current.versionCode.toLong()
                                val versionText = tag.removePrefix("v").substringBefore("-")
                                val parts = versionText.split(".").mapNotNull { it.toIntOrNull() }
                                val remoteVersion = if (parts.size >= 3) parts[0] * 10000L + parts[1] * 100L + parts[2] else -1L
                                if (remoteVersion in 0..currentVersion)
                                    error("Установлена версия ${current.versionName}; релиз ${tag} не новее. Проверь versionCode/versionName в сборке.")
                                val target = File(context.cacheDir, "updates/nexus-${tag}.apk")
                                target.parentFile?.mkdirs()
                                val download = (URL(apk.getString("browser_download_url")).openConnection() as HttpURLConnection).apply {
                                    connectTimeout = 15000
                                    readTimeout = 120000
                                    setRequestProperty("User-Agent", "NEXUS-AI-Updater")
                                }
                                download.inputStream.use { input -> target.outputStream().use { output -> input.copyTo(output) } }
                                download.disconnect()
                                val archive = context.packageManager.getPackageArchiveInfo(target.absolutePath,
                                    PackageManager.GET_SIGNING_CERTIFICATES) ?: error("APK не прошёл разбор Android PackageManager.")
                                if (archive.packageName != context.packageName) error("APK принадлежит другому приложению: ${archive.packageName}")
                                val archiveVersion = if (Build.VERSION.SDK_INT >= 28) archive.longVersionCode else archive.versionCode.toLong()
                                if (archiveVersion <= currentVersion) error("APK не является более новой версией установленного приложения.")
                                val installedInfo = context.packageManager.getPackageInfo(context.packageName,
                                    PackageManager.GET_SIGNING_CERTIFICATES)
                                val installedSigners = installedInfo.signingInfo?.apkContentsSigners?.map {
                                    MessageDigest.getInstance("SHA-256").digest(it.toByteArray()).joinToString("") { b -> "%02x".format(b) }
                                }?.toSet().orEmpty()
                                val archiveSigners = archive.signingInfo?.apkContentsSigners?.map {
                                    MessageDigest.getInstance("SHA-256").digest(it.toByteArray()).joinToString("") { b -> "%02x".format(b) }
                                }?.toSet().orEmpty()
                                if (installedSigners.isEmpty() || archiveSigners.isEmpty() || installedSigners != archiveSigners)
                                    error("Подпись APK не совпадает с установленной версией. Обновление остановлено, данные не затронуты.")
                                target
                            }
                            downloadedFile = result
                            status = "APK загружен и проверен. Совпадают package name и сертификат подписи; версия новее."
                        } catch (e: Exception) {
                            status = "Не удалось обновить: ${e.message ?: e.javaClass.simpleName}"
                        } finally { busy = false }
                    }
                }) { Text("Проверить обновления") }
                OutlinedButton(enabled = !busy && downloadedFile?.isFile == true, onClick = {
                    downloadedFile?.let(::installDownloadedApk)
                }) { Text("Установить") }
            }
        }
    }
}
