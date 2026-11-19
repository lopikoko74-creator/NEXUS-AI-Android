package ai.nexus.mobile

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager

object InstalledAppLauncher {
    sealed interface Result {
        data class Launched(val appLabel: String) : Result
        data class NotFound(val requestedName: String) : Result
        data class Failed(val reason: String) : Result
    }

    @Suppress("DEPRECATION")
    fun listLaunchableApps(context: Context, limit: Int = 100): List<String> {
        val query = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return context.packageManager.queryIntentActivities(query, PackageManager.MATCH_ALL)
            .mapNotNull { info ->
                val appInfo = info.activityInfo?.applicationInfo ?: return@mapNotNull null
                context.packageManager.getApplicationLabel(appInfo).toString().trim()
                    .takeIf { it.isNotEmpty() }
            }
            .distinctBy { VoiceAliasResolver.normalize(it).lowercase() }
            .sortedBy { it.lowercase() }
            .take(limit.coerceIn(1, 150))
    }

    @Suppress("DEPRECATION")
    fun launch(context: Context, requestedName: String): Result {
        val wanted = VoiceAliasResolver.normalize(requestedName).trim()
        if (wanted.isBlank()) return Result.Failed("Не указано приложение")

        val query = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val candidates = context.packageManager.queryIntentActivities(
            query,
            PackageManager.MATCH_ALL
        )

        val match = candidates
            .mapNotNull { resolveInfo ->
                val appInfo = resolveInfo.activityInfo?.applicationInfo ?: return@mapNotNull null
                val label = context.packageManager.getApplicationLabel(appInfo).toString()
                val normalizedLabel = VoiceAliasResolver.normalize(label).trim()
                Triple(resolveInfo, label, normalizedLabel)
            }
            .sortedBy { (_, label, _) -> label.length }
            .firstOrNull { (_, _, label) -> label.equals(wanted, ignoreCase = true) }

        if (match == null) return Result.NotFound(wanted)
        val packageName = match.first.activityInfo?.packageName
            ?: return Result.Failed("Не удалось определить пакет приложения")
        return try {
            val launchIntent = context.packageManager.getLaunchIntentForPackage(packageName)
                ?: return Result.Failed("У приложения нет доступного экрана запуска")
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(launchIntent)
            Result.Launched(match.second)
        } catch (error: Exception) {
            Result.Failed(error.message ?: "Android не разрешил запуск приложения")
        }
    }
}
