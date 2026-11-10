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

    /**
     * The base grant every authenticated request needs: it covers reading the
     * authenticated account's own identity without granting repository
     * writes. A read-only client sends exactly this.
     */
    const val AT_PROTO = "atproto"

    /**
     * Creating a play record. Create only: the play queue writes
     * `createRecord` with a client-generated TID, so retries pick a fresh
     * record rather than mutating an existing one.
     */
    const val PLAY_CREATE = "repo:fm.teal.feed.play?action=create"

    /** Creating or updating the single `literal:self` profile record. */
    const val PROFILE_WRITE = "repo:fm.teal.actor.profile?action=create&action=update"

    /**
     * Setting, replacing or clearing the single `literal:self` status record.
     * Replacing an existing record needs `update`; `create` alone would fail
     * the second write, and `delete` is what clears a now-playing status.
     */
    const val STATUS_WRITE =
        "repo:fm.teal.actor.status?action=create&action=update&action=delete"

    /**
     * Every scope a full read/write Fathom would request, including the
     * `atproto` base. This list is the single source of truth for the
     * `scope` string in `website/client-metadata.json`; the JVM drift test
     * fails if the two disagree. Unused today.
     */
    val all: List<String> = listOf(AT_PROTO, PLAY_CREATE, PROFILE_WRITE, STATUS_WRITE)

    /** The space-joined `scope` value for a full read/write client. */
    val fullWriteRequest: String get() = all.joinToString(" ")
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
