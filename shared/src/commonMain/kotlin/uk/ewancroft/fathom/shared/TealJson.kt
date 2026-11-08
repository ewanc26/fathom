package uk.ewancroft.fathom.shared

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import uk.ewancroft.fathom.shared.model.ActorSearchPage
import uk.ewancroft.fathom.shared.model.Play
import uk.ewancroft.fathom.shared.model.RecordEntry
import uk.ewancroft.fathom.shared.model.RecordPage
import uk.ewancroft.fathom.shared.model.TealProfile
import uk.ewancroft.fathom.shared.model.TealStatus

/**
 * The one place JSON is turned into models.
 *
 * Both apps hand raw response bodies here, so neither platform needs its own
 * decoder and neither can drift on how a missing field is treated.
 *
 * Records are decoded one at a time on purpose. `kotlinx.serialization` fails
 * a list on the first element it cannot read, and a repo legitimately holds
 * records written by older clients: one malformed play must cost one play,
 * not the page it arrived in. Bad elements are counted in
 * [RecordPage.skipped] so the UI can say a figure is approximate.
 */
object TealJson {

    val format: Json = Json {
        ignoreUnknownKeys = true
        // Not lenient: these bodies are machine-written, so a number where the
        // lexicon says string is corruption rather than a quirk worth
        // accepting. Leniency would silently turn `"trackName": 5` into the
        // track "5" and hide the fact that anything was wrong.
        isLenient = false
        explicitNulls = false
    }

    /**
     * A page of play records, with each record's envelope URI and CID copied
     * onto the play.
     *
     * The values alone cannot be deduplicated: a repeat of one track is
     * legitimate, while two overlapping pages can contain the same record. The
     * envelope URI is the only stable identity.
     */
    fun decodePlays(json: String): RecordPage<Play> {
        val page = decodeRecords<Play>(json)
        return page.copy(
            records = page.records.map { entry ->
                entry.copy(
                    value = entry.value.copy(
                        uri = entry.uri.takeIf { it.isNotBlank() },
                        cid = entry.cid,
                    ),
                )
            },
        )
    }

    /** A page holding teal profile records. */
    fun decodeProfiles(json: String): RecordPage<TealProfile> = decodeRecords(json)

    /** A page holding teal status records. */
    fun decodeStatuses(json: String): RecordPage<TealStatus> = decodeRecords(json)

    /**
     * Decodes `records[].value` as [T], keeping every element that parses.
     *
     * A body that is not an object with a `records` array is reported as
     * `parsed = false` rather than as an empty repo, because "this account
     * has never scrobbled" and "the server answered something unexpected"
     * need different words on screen.
     */
    inline fun <reified T> decodeRecords(json: String): RecordPage<T> {
        val root = runCatching { format.parseToJsonElement(json).jsonObject }.getOrNull()
            ?: return RecordPage(parsed = false)
        val array = root["records"] as? JsonArray
            ?: return RecordPage(parsed = false)

        val entries = ArrayList<RecordEntry<T>>(array.size)
        var skipped = 0
        for (element in array) {
            val record = element as? JsonObject
            val value = record?.get("value")
            if (value == null) {
                skipped++
                continue
            }
            val decoded = runCatching { format.decodeFromJsonElement<T>(value) }.getOrNull()
            if (decoded == null) {
                skipped++
                continue
            }
            entries += RecordEntry(
                uri = (record["uri"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
                cid = (record["cid"] as? JsonPrimitive)?.contentOrNull,
                value = decoded,
            )
        }

        return RecordPage(
            records = entries,
            cursor = (root["cursor"] as? JsonPrimitive)?.contentOrNull,
            skipped = skipped,
            parsed = true,
        )
    }

    /** The `value` of a single `com.atproto.repo.getRecord` response. */
    inline fun <reified T> decodeRecordValue(json: String): T? {
        val root = runCatching { format.parseToJsonElement(json).jsonObject }.getOrNull() ?: return null
        val value = root["value"] ?: return null
        return runCatching { format.decodeFromJsonElement<T>(value) }.getOrNull()
    }

    /**
     * A single record response, keeping its URI and CID alongside the value.
     *
     * Used for `fm.teal.actor.status`, which is one record at a known key and
     * therefore does not need a listing.
     */
    inline fun <reified T> decodeSingleRecord(json: String): RecordEntry<T>? {
        val root = runCatching { format.parseToJsonElement(json).jsonObject }.getOrNull() ?: return null
        val value = root["value"] ?: return null
        val decoded = runCatching { format.decodeFromJsonElement<T>(value) }.getOrNull() ?: return null
        return RecordEntry(
            uri = (root["uri"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
            cid = (root["cid"] as? JsonPrimitive)?.contentOrNull,
            value = decoded,
        )
    }

    /** `fm.teal.actor.searchActors` output. */
    fun decodeActorSearch(json: String): ActorSearchPage? =
        runCatching { format.decodeFromString<ActorSearchPage>(json) }.getOrNull()

    /** The `did` of a `com.atproto.identity.resolveHandle` response. */
    fun decodeDid(json: String): String? {
        val root = runCatching { format.parseToJsonElement(json).jsonObject }.getOrNull() ?: return null
        return (root["did"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
    }

    /**
     * The PDS endpoint from a DID document.
     *
     * A DID document lists services; the one a client reads and writes with
     * is typed `AtprotoPersonalDataServer`. There is only ever one, and a
     * document without it means the account cannot be read this way at all.
     */
    fun decodePdsEndpoint(json: String): String? {
        val root = runCatching { format.parseToJsonElement(json).jsonObject }.getOrNull() ?: return null
        val services = root["service"] as? JsonArray ?: return null
        for (element in services) {
            val service = element as? JsonObject ?: continue
            val type = (service["type"] as? JsonPrimitive)?.contentOrNull
            if (type != "AtprotoPersonalDataServer") continue
            val endpoint = (service["serviceEndpoint"] as? JsonPrimitive)?.contentOrNull
            if (!endpoint.isNullOrBlank()) return endpoint.trimEnd('/')
        }
        return null
    }

    /** The `error` field of an XRPC error body, for a message worth showing. */
    fun decodeError(json: String): String? {
        val root = runCatching { format.parseToJsonElement(json).jsonObject }.getOrNull() ?: return null
        val error = (root["error"] as? JsonPrimitive)?.contentOrNull
        val message = (root["message"] as? JsonPrimitive)?.contentOrNull
        return listOfNotNull(error, message).joinToString(": ").ifBlank { null }
    }
}
