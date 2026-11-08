package uk.ewancroft.fathom.shared.cache

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import uk.ewancroft.fathom.shared.model.Play
import uk.ewancroft.fathom.shared.model.TealProfile
import uk.ewancroft.fathom.shared.model.TealStatus

/**
 * One account's history as it was last read, ready to be written to disk.
 *
 * This is what makes the second launch instant. The repo is still the source
 * of truth — a saved copy is only ever the starting picture, replaced page by
 * page as the real read arrives — but reading twenty thousand plays costs
 * minutes, and paying that again on every launch to redraw figures that have
 * not changed is not a virtue.
 *
 * [identity] is the string the person typed (a handle or a DID), not [did],
 * because that is the key the platform stores and the value the next launch
 * has to match. [sampled] travels with the plays: a history that stopped at
 * the runaway guard or on a failed page is still worth saving, and drawing it
 * from disk must keep the "only part of this was read" label it earned.
 * [version] exists so an incompatible file can be discarded rather than
 * misread. It has no default, so a file written before the field existed is
 * discarded too; a cache is always safe to delete.
 */
@Serializable
data class CachedHistory(
    val version: Int,
    val identity: String,
    val did: String,
    val handle: String? = null,
    val profile: TealProfile? = null,
    val status: TealStatus? = null,
    val plays: List<Play> = emptyList(),
    val skipped: Int = 0,
    val legacy: Boolean = false,
    val sampled: Boolean = false,
    val savedAtEpochSeconds: Long = 0L,
)

/**
 * The cache file's one reader and writer.
 *
 * Both platforms write the same JSON with this, so a file is not tied to the
 * app that produced it and the decoding rules are testable in common code.
 * Nothing here touches the filesystem: each platform supplies a path, which
 * keeps this module free of platform types like every other part of the core.
 */
object HistoryCache {
    /**
     * Version 3 is the first cache whose completeness flag can be trusted as
     * an incremental stopping point. It also stores each play's AT URI and
     * CID. Older files are discarded, so upgrading starts with a full read.
     */
    const val VERSION = 3

    /**
     * Lenient about unknown keys so a newer app can still read an older file,
     * and strict about the version and the identifying fields, because a file
     * missing either cannot be matched to an account.
     */
    private val format = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    fun encode(entry: CachedHistory): String = format.encodeToString(entry)

    /** The saved history, or null when the file is unreadable, truncated or from another version. */
    fun decode(json: String): CachedHistory? {
        val entry = runCatching { format.decodeFromString<CachedHistory>(json) }.getOrNull()
            ?: return null
        if (entry.version != VERSION) return null
        if (entry.identity.isBlank() || entry.did.isBlank()) return null
        return entry
    }
}
