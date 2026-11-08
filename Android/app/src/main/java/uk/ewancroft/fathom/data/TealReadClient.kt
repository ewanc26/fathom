package uk.ewancroft.fathom.data

/**
 * The transport surface [FathomRepository] needs.
 *
 * The concrete client is still the only thing that talks HTTP; this interface
 * exists so paging, partial-read labelling and cancellation can be tested
 * against canned pages instead of a network. It is deliberately read-only:
 * Fathom never writes a record.
 */
interface TealReadClient {
    suspend fun resolveIdentity(input: String): String

    suspend fun pdsFor(did: String): String

    suspend fun listRecords(
        pds: String,
        repo: String,
        collection: String,
        cursor: String?,
        limit: Int,
    ): String

    suspend fun getRecord(
        pds: String,
        repo: String,
        collection: String,
        rkey: String,
    ): String?
}
