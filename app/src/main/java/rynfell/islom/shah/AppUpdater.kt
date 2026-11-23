package rynfell.islom.shah

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONException
import org.json.JSONObject

/** GitHub repository whose releases carry the signed APK. */
internal const val updateRepository = "UmarTuranov/Shah"

internal data class AppUpdate(val version: String, val apkUrl: String, val sizeBytes: Long)

internal sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data class Available(val update: AppUpdate) : UpdateState
    data class Downloading(val update: AppUpdate, val progress: Float) : UpdateState
    data object Installing : UpdateState
    data object Failed : UpdateState
}

/** Returns the newest release if it is newer than [currentVersion], or null when up to date. */
internal suspend fun fetchLatestUpdate(currentVersion: String): AppUpdate? = withContext(Dispatchers.IO) {
    val connection = URL("https://api.github.com/repos/$updateRepository/releases/latest")
        .openConnection() as HttpURLConnection
    try {
        connection.connectTimeout = 8_000
        connection.readTimeout = 8_000
        connection.setRequestProperty("Accept", "application/vnd.github+json")
        if (connection.responseCode == HttpURLConnection.HTTP_NOT_FOUND) return@withContext null
        if (connection.responseCode != HttpURLConnection.HTTP_OK) throw IOException("HTTP ${connection.responseCode}")
        parseUpdate(connection.inputStream.bufferedReader().use { it.readText() }, currentVersion)
    } finally {
        connection.disconnect()
    }
}

internal fun parseUpdate(json: String, currentVersion: String): AppUpdate? {
    try {
        val release = JSONObject(json)
        if (release.optBoolean("draft") || release.optBoolean("prerelease")) return null
        val tag = release.getString("tag_name")
        if (!isNewerVersion(tag, currentVersion)) return null
        val assets = release.getJSONArray("assets")
        for (i in 0 until assets.length()) {
            val asset = assets.getJSONObject(i)
            if (asset.getString("name").endsWith(".apk", ignoreCase = true)) {
                return AppUpdate(tag.removePrefix("v"), asset.getString("browser_download_url"), asset.optLong("size"))
            }
        }
        return null
    } catch (error: JSONException) {
        throw IOException("Invalid release response", error)
    }
}

internal fun isNewerVersion(candidate: String, current: String): Boolean {
    fun parts(version: String) = version.removePrefix("v").substringBefore('-')
        .split('.').map { it.toIntOrNull() ?: 0 }
    val a = parts(candidate)
    val b = parts(current)
    for (i in 0 until maxOf(a.size, b.size)) {
        val x = a.getOrElse(i) { 0 }
        val y = b.getOrElse(i) { 0 }
        if (x != y) return x > y
    }
    return false
}

/** Downloads the APK, then hands it to the system installer, which asks the user to confirm. */
internal suspend fun downloadAndInstall(
    context: Context,
    update: AppUpdate,
    onProgress: (Float) -> Unit,
) = withContext(Dispatchers.IO) {
    val file = File(context.cacheDir, "update.apk")
    val connection = URL(update.apkUrl).openConnection() as HttpURLConnection
    try {
        connection.connectTimeout = 15_000
        connection.readTimeout = 15_000
        if (connection.responseCode != HttpURLConnection.HTTP_OK) throw IOException("HTTP ${connection.responseCode}")
        val total = connection.contentLengthLong.takeIf { it > 0 } ?: update.sizeBytes
        connection.inputStream.use { input ->
            file.outputStream().use { output ->
                val buffer = ByteArray(64 * 1024)
                var done = 0L
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    output.write(buffer, 0, read)
                    done += read
                    if (total > 0) onProgress((done.toFloat() / total).coerceIn(0f, 1f))
                }
            }
        }
    } finally {
        connection.disconnect()
    }
    install(context, file)
}

private fun install(context: Context, file: File) {
    val installer = context.packageManager.packageInstaller
    val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
    val id = installer.createSession(params)
    installer.openSession(id).use { session ->
        file.inputStream().use { input ->
            session.openWrite("update.apk", 0, file.length()).use { output ->
                input.copyTo(output)
                session.fsync(output)
            }
        }
        val callback = PendingIntent.getBroadcast(
            context, id,
            Intent(context, UpdateInstallReceiver::class.java).setPackage(context.packageName),
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        session.commit(callback.intentSender)
    }
}

class UpdateInstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -1) == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            @Suppress("DEPRECATION")
            val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
            context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        File(context.cacheDir, "update.apk").delete()
    }
}
