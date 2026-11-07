package uk.ewancroft.fathom.shared

/**
 * An `at://` URI, split into its three parts.
 *
 * `at://did:plc:abc/fm.teal.feed.play/3mzq` addresses one record;
 * `at://did:plc:abc` addresses an actor; `at://did:plc:abc/fm.teal.feed.play`
 * addresses a collection. AT Protocol calls the middle part the collection
 * and the last the record key, and both are optional.
 */
data class AtUri(
    val authority: String,
    val collection: String? = null,
    val recordKey: String? = null,
) {

    fun toUri(): String = buildString {
        append(SCHEME)
        append(authority)
        if (collection != null) {
            append('/')
            append(collection)
            if (recordKey != null) {
                append('/')
                append(recordKey)
            }
        }
    }

    companion object {
        const val SCHEME = "at://"

        /**
         * Parses [raw], returning null for anything that is not a usable
         * `at://` URI.
         *
         * Deliberately strict about the shape and deliberately permissive
         * about which DID method the authority uses: teal.fm accounts live
         * on whichever PDS they chose, and `did:web` is as valid as `did:plc`.
         */
        fun parse(raw: String?): AtUri? {
            if (raw.isNullOrBlank()) return null
            val text = raw.trim()
            if (!text.startsWith(SCHEME, ignoreCase = true)) return null

            val body = text.substring(SCHEME.length)
            if (body.isEmpty()) return null
            // A query or fragment means this is not a record URI we can use.
            if (body.any { it == '?' || it == '#' }) return null

            val parts = body.split('/')
            if (parts.size > 3) return null
            val authority = parts[0]
            if (!isAuthority(authority)) return null

            val collection = parts.getOrNull(1)?.takeIf { it.isNotEmpty() }
            if (parts.size >= 2 && collection == null) return null
            if (collection != null && !isCollection(collection)) return null

            val recordKey = parts.getOrNull(2)?.takeIf { it.isNotEmpty() }
            if (parts.size == 3 && recordKey == null) return null
            if (recordKey != null && !isRecordKey(recordKey)) return null

            return AtUri(authority, collection, recordKey)
        }

        /** True for a DID or a handle. */
        fun isAuthority(value: String): Boolean =
            value.startsWith("did:") && value.length > 8 || isHandle(value)

        /**
         * A handle: dot-separated labels, no scheme, at least two labels.
         * Deliberately loose on the top label because `.test` and punycode
         * hosts are both real.
         */
        fun isHandle(value: String): Boolean {
            if (value.length < 3 || value.length > 253) return false
            if (!value.contains('.')) return false
            if (value.startsWith('.') || value.endsWith('.')) return false
            return value.split('.').all { label ->
                label.isNotEmpty() &&
                    label.length <= 63 &&
                    label.all { it.isLetterOrDigit() || it == '-' } &&
                    !label.startsWith('-') &&
                    !label.endsWith('-')
            }
        }

        /** An NSID: reverse-DNS authority plus a name, at least three segments. */
        fun isCollection(value: String): Boolean {
            val pieces = value.split('.')
            if (pieces.size < 3) return false
            if (pieces.any { it.isEmpty() }) return false
            // The final segment is the name; it must not start with a digit.
            val name = pieces.last()
            if (!name.first().isLetter()) return false
            return pieces.all { piece ->
                piece.all { it.isLetterOrDigit() || it == '-' }
            }
        }

        /** A record key: any non-empty string without a slash or whitespace. */
        fun isRecordKey(value: String): Boolean =
            value.isNotEmpty() && value.none { it == '/' || it.isWhitespace() }
    }
}
