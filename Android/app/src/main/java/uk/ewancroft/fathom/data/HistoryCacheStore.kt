package uk.ewancroft.fathom.data

import android.content.Context
import java.io.File
import uk.ewancroft.fathom.shared.cache.CachedHistory
import uk.ewancroft.fathom.shared.cache.HistoryCache

/**
 * The saved history, in app-private files rather than preferences.
 *
 * A completed read of a large repo is tens of thousands of plays, which is
 * megabytes: far too much for SharedPreferences, and not something to put in
 * the cache directory either, because the point of it is to survive until the
 * next launch rather than until Android needs the space.
 *
 * Writes go to a temporary file first and are renamed over the old one, so a
 * process killed mid-write leaves the previous copy intact rather than half a
 * JSON document.
 */
class HistoryCacheStore(context: Context) {

    private val directory = File(context.applicationContext.filesDir, "fathom")
    private val file = File(directory, "history.json")

    /** The saved history for [identity], or null when there is none or it is unreadable. */
    fun read(identity: String): CachedHistory? {
        if (!file.exists()) return null
        val text = runCatching { file.readText() }.getOrNull() ?: return null
        return HistoryCache.decode(text)?.takeIf { it.identity == identity }
    }

    fun write(entry: CachedHistory) {
        runCatching {
            directory.mkdirs()
            val temporary = File(directory, "history.json.tmp")
            temporary.writeText(HistoryCache.encode(entry))
            if (!temporary.renameTo(file)) {
                file.delete()
                temporary.renameTo(file)
            }
        }
    }

    fun clear() {
        runCatching { file.delete() }
    }
}
