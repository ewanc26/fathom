package uk.ewancroft.fathom.shared.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * A content-addressed reference to a blob, as a record stores it.
 *
 * The CID arrives as `{"$link": "bafk..."}`, not as a bare string, because
 * this is DAG-JSON and not the AppView's flattened view.
 */
@Serializable
data class BlobRef(
    @SerialName("\$type") val type: String? = null,
    val ref: CidLink? = null,
    val mimeType: String? = null,
    val size: Long? = null,
) {
    /** The CID this blob can be fetched by, or null for a malformed reference. */
    fun cid(): String? = ref?.link?.takeIf { it.isNotBlank() }
}

@Serializable
data class CidLink(
    @SerialName("\$link") val link: String? = null,
)

/**
 * An `fm.teal.actor.profile` record.
 *
 * Its key is `literal:self`, so a repo holds at most one. The avatar and
 * banner are blobs on the author's PDS, not URLs; turning a CID into a URL is
 * the transport's job and needs the PDS that holds it.
 */
@Serializable
data class TealProfile(
    @SerialName("\$type") val type: String? = null,
    val displayName: String? = null,
    val description: String? = null,
    val featuredItem: FeaturedItem? = null,
    val avatar: BlobRef? = null,
    val banner: BlobRef? = null,
    val createdAt: String? = null,
) {
    fun nameOrNull(): String? = displayName?.trim()?.takeIf { it.isNotEmpty() }
}

/**
 * The item a person pinned to the top of their teal.fm profile.
 *
 * `type` is an open known-values list in the lexicon, so an unknown type is
 * kept rather than rejected; the UI simply does not know how to label it.
 */
@Serializable
data class FeaturedItem(
    val mbid: String? = null,
    val type: String? = null,
)

/**
 * An `fm.teal.actor.status` record: what someone is listening to now.
 *
 * One record per repo, keyed `literal:self`. `expiry` is the point after
 * which the status is stale even though the record still exists, and the
 * lexicon asks clients to assume ten minutes after `time` when it is absent.
 *
 * Real records exist with an empty `trackName` and an empty artist array —
 * a tracker with nothing to report still writes a status — so [isListening]
 * checks for a track rather than for the presence of the record.
 */
@Serializable
data class TealStatus(
    @SerialName("\$type") val type: String? = null,
    val time: String? = null,
    val expiry: String? = null,
    val item: Play? = null,
) {
    /** True when this status names a track and has not expired by [nowEpochSeconds]. */
    fun isListening(nowEpochSeconds: Long, expiryEpochSeconds: Long?): Boolean {
        val track = item ?: return false
        if (!track.hasTrack()) return false
        val end = expiryEpochSeconds ?: return true
        return nowEpochSeconds < end
    }
}

/**
 * `fm.teal.actor.defs#miniProfileView`, as `fm.teal.actor.searchActors` returns it.
 *
 * Only returned by the AppView. Fathom's dashboard reads repos directly, so
 * this is used for search and for a person's display name when their repo has
 * no teal profile record of its own.
 */
@Serializable
data class MiniProfileView(
    val did: String? = null,
    val handle: String? = null,
    val displayName: String? = null,
    val avatar: String? = null,
) {
    fun label(): String = displayName?.trim()?.takeIf { it.isNotEmpty() }
        ?: handle
        ?: did
        ?: "Unknown account"
}

/** An AppView actor search result page. */
@Serializable
data class ActorSearchPage(
    val actors: List<MiniProfileView> = emptyList(),
    val cursor: String? = null,
)
