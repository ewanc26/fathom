package uk.ewancroft.fathom.data

import android.content.Context
import uk.ewancroft.fathom.shared.stats.Period

/**
 * The one thing Fathom remembers between launches: whose dashboard to show.
 *
 * A handle or DID is not a credential — every record it addresses is public —
 * so ordinary app-private preferences are the right place for it. Nothing
 * here is secret, and nothing here is required to boot: a missing value just
 * means the connect screen is shown.
 */
class IdentityStore(context: Context) {

    private val preferences = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun identity(): String? = preferences.getString(KEY_IDENTITY, null)?.takeIf { it.isNotBlank() }

    fun saveIdentity(value: String) {
        preferences.edit().putString(KEY_IDENTITY, value).apply()
    }

    fun clearIdentity() {
        preferences.edit().remove(KEY_IDENTITY).apply()
    }

    fun period(): Period = Period.fromId(preferences.getString(KEY_PERIOD, null))

    fun savePeriod(period: Period) {
        preferences.edit().putString(KEY_PERIOD, period.id).apply()
    }

    private companion object {
        const val FILE = "fathom.prefs"
        const val KEY_IDENTITY = "identity"
        const val KEY_PERIOD = "period"
    }
}
