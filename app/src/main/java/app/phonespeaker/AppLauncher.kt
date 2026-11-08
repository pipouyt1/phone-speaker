package app.phonespeaker

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import java.text.Normalizer
import java.util.Locale
import kotlin.math.abs

/** "Abre Spotify": finds the installed app a spoken command asks for. */
object AppLauncher {
    class Entry(val label: String, val packageName: String, val normalized: String)

    private val verbs = "abre|abrir|abri|abreme|abrime|inicia|iniciar|lanza|lanzar|ejecuta|ejecutar|open|launch|start"
    private val command = Regex("^(?:por favor )?(?:$verbs) (?:(?:la|el|las|los|mi|me) )?(?:(?:aplicacion|app|programa) )?(?:de )?(.+?)(?: por favor)?$")

    private var cache: List<Entry> = emptyList()
    private var cachedAt = 0L

    /** Lowercase, no accents, no punctuation: so "Cámara." and "camara" compare equal. */
    fun normalize(text: String): String =
        Normalizer.normalize(text.lowercase(Locale.ROOT), Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
            .replace(Regex("[^a-z0-9 ]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    /** The app name asked for in "abre Spotify", or null when the sentence is not an open command. */
    fun requestedName(text: String): String? =
        command.matchEntire(normalize(text))?.groupValues?.get(1)?.takeIf { it.isNotBlank() }

    private fun apps(ctx: Context): List<Entry> {
        val now = SystemClock.elapsedRealtime()
        if (cache.isNotEmpty() && now - cachedAt < 60_000L) return cache
        val pm = ctx.packageManager
        val launchable = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        cache = pm.queryIntentActivities(launchable, 0)
            .filter { it.activityInfo.packageName != ctx.packageName }
            .map { val label = it.loadLabel(pm).toString(); Entry(label, it.activityInfo.packageName, normalize(label)) }
            .filter { it.normalized.isNotEmpty() }
            .distinctBy { it.packageName }
        cachedAt = now
        return cache
    }

    /**
     * The installed app that the sentence asks to open, if it is an open command for an app that
     * exists. Anything else ("abre la persiana") is left to Home Assistant.
     */
    fun find(ctx: Context, text: String): Entry? {
        val name = requestedName(text) ?: return null
        val all = apps(ctx)
        all.firstOrNull { it.normalized == name }?.let { return it }
        return all
            .filter { it.normalized.length >= 4 && (it.normalized.startsWith(name) || name.startsWith(it.normalized) || it.normalized.contains(name) || name.contains(it.normalized)) }
            .minByOrNull { abs(it.normalized.length - name.length) }
    }

    fun launchIntent(ctx: Context, entry: Entry): Intent? =
        ctx.packageManager.getLaunchIntentForPackage(entry.packageName)?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}
