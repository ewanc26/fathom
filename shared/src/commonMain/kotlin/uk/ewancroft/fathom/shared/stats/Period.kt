package uk.ewancroft.fathom.shared.stats

/**
 * The windows the dashboard can show.
 *
 * The ids are teal.fm's own period values — `7days`, `30days`, `all` — so the
 * same selection can be handed to `fm.teal.stats.getTopArtists` unchanged if
 * an AppView is configured. Fathom computes its figures from the repo today,
 * but the vocabulary should not have to change to adopt one.
 */
enum class Period(val id: String, val label: String, val windowDays: Int?) {
    SevenDays("7days", "7 days", 7),
    ThirtyDays("30days", "30 days", 30),
    AllTime("all", "All time", null);

    val isAllTime: Boolean get() = windowDays == null

    companion object {
        fun fromId(id: String?): Period = entries.firstOrNull { it.id == id } ?: ThirtyDays
    }
}
