package uk.ewancroft.fathom.shared.auth

/**
 * The OAuth scopes Fathom would need to write anything.
 *
 * Fathom is a reader. It never writes, holds no tokens and therefore never
 * asks for these. They are recorded here because the moment a "mark as
 * listened" or "set a featured item" action is added, the client metadata
 * hosted at the project's origin has to request the matching scope and the
 * two must not drift.
 *
 * Scope grammar is `repo:<collection>?action=<action>`, repeated actions
 * joined by `&`. A scope can only name collections in the client's own
 * namespace unless it uses `include:`, which is why a teal.fm client needs
 * teal's collections named explicitly rather than one blanket grant.
 */
object TealScopes {

    /** Creating a play record. */
    const val PLAY_CREATE = "repo:fm.teal.feed.play?action=create"

    /** Creating or updating the single `literal:self` profile record. */
    const val PROFILE_WRITE = "repo:fm.teal.actor.profile?action=create&action=update"

    /** Replacing or clearing the single `literal:self` status record. */
    const val STATUS_WRITE = "repo:fm.teal.actor.status?action=create&action=delete"

    /** Every scope a full read/write Fathom would request. Unused today. */
    val all: List<String> = listOf(PLAY_CREATE, PROFILE_WRITE, STATUS_WRITE)

    /**
     * The `scope` value a read-only client sends.
     *
     * `atproto` is the base grant: it covers reading the authenticated
     * account's own identity without granting repository writes.
     */
    const val READ_ONLY = "atproto"
}

/** The records Fathom reads, described once for the UI's provenance line. */
object Provenance {
    const val PRODUCTION_NAMESPACE = "fm.teal.*"
    const val LEGACY_NAMESPACE = "fm.teal.alpha.*"

    /**
     * The sentence shown wherever a figure is computed rather than fetched.
     *
     * The dashboard aggregates the records in the repo itself. teal.fm's own
     * stats endpoints aggregate across the network; a figure from one is not
     * interchangeable with a figure from the other, and the UI says which it
     * is showing.
     */
    const val COMPUTED_LOCALLY = "Computed from the plays in this account's repo"
}
