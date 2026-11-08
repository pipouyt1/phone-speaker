package app.phonespeaker

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import okhttp3.Request
import org.json.JSONObject
import java.io.File

/** A newer version published as a GitHub release. */
data class Release(val version: String, val notes: String, val apkUrl: String, val size: Long)

/** Looks for a newer APK in the project's GitHub releases, downloads it and hands it to Android's installer. */
object Updater {
    private const val LATEST = "https://api.github.com/repos/pipouyt1/phone-speaker/releases/latest"

    /** The newer release, or null when this version is the latest. Blocking: call off the main thread. */
    fun check(current: String): Result<Release?> = try {
        val request = Request.Builder().url(LATEST).header("Accept", "application/vnd.github+json").build()
        HaClient.httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return Result.failure(Exception("GitHub respondió ${response.code}"))
            val json = JSONObject(response.body?.string().orEmpty())
            val version = json.optString("tag_name").removePrefix("v")
            val assets = json.optJSONArray("assets")
            var apk: JSONObject? = null
            for (i in 0 until (assets?.length() ?: 0)) {
                val asset = assets!!.getJSONObject(i)
                if (asset.optString("name").endsWith(".apk")) { apk = asset; break }
            }
            when {
                version.isEmpty() || apk == null -> Result.success(null)
                !isNewer(version, current) -> Result.success(null)
                else -> Result.success(
                    Release(version, json.optString("body").trim(), apk.optString("browser_download_url"), apk.optLong("size"))
                )
            }
        }
    } catch (e: Exception) {
        Result.failure(Exception("No se pudo consultar: ${e.message}"))
    }

    /** Download the APK; [progress] gets 0..1. Blocking: call off the main thread. */
    fun download(ctx: Context, release: Release, progress: (Float) -> Unit): Result<File> = try {
        val dir = File(ctx.cacheDir, "updates").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
        val file = File(dir, "phone-speaker-${release.version}.apk")
        HaClient.httpClient.newCall(Request.Builder().url(release.apkUrl).build()).execute().use { response ->
            val body = response.body
            if (!response.isSuccessful || body == null) return Result.failure(Exception("Descarga fallida (${response.code})"))
            val total = body.contentLength().takeIf { it > 0 } ?: release.size
            var done = 0L
            body.byteStream().use { input ->
                file.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        output.write(buffer, 0, n)
                        done += n
                        if (total > 0) progress((done.toFloat() / total).coerceIn(0f, 1f))
                    }
                }
            }
        }
        Result.success(file)
    } catch (e: Exception) {
        Result.failure(Exception("Descarga fallida: ${e.message}"))
    }

    /** True when Android lets this app install packages; otherwise the settings page for it is opened. */
    fun ensureCanInstall(ctx: Context): Boolean {
        if (ctx.packageManager.canRequestPackageInstalls()) return true
        ctx.startActivity(
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        return false
    }

    fun install(ctx: Context, file: File) {
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", file)
        ctx.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    private fun isNewer(candidate: String, current: String): Boolean {
        fun parts(v: String) = v.substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
        val a = parts(candidate)
        val b = parts(current)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }
}
