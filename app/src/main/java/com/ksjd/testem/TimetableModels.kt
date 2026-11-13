package com.ksjd.testem

/** Planner scope covering all of Slovakia, city buses included. */
const val PLANNER_ALL = "vlakbusmhd"

data class CpStopSuggestion(
    val selectedText: String,
    val value: String,
    val value2: String,
    val coorX: String? = null,
    val coorY: String? = null,
    val description: String = ""
) {
    fun toHiddenFieldValue(): String = "$selectedText%$value%$value2"
    fun toPositionFieldValue(): String = if (!coorX.isNullOrBlank() && !coorY.isNullOrBlank()) {
        "$coorX%$coorY"
    } else {
        ""
    }
}

data class TimetableSegment(
    val line: String,
    val operatorName: String,
    val departureTime: String,
    val departureStop: String,
    val arrivalTime: String,
    val arrivalStop: String,
    val departurePlatform: String = "",
    val arrivalPlatform: String = "",
    /** Trip link with every stop of this bus (TimetableService.tripUrl). */
    val routeUrl: String = "",
    /** ISO date of [departureTime]. */
    val serviceDate: String = "",
    /** 2.17.0: how often the change onto this bus worked on earlier days ([transferRuns] 0 = not known). */
    val transferWorked: Int = 0,
    val transferRuns: Int = 0,
    /** 2.17.2: where the bus goes on as another line, riders staying on: (line, stop). Null when it doesn't (and in older saved copies). */
    val goesOnAs: List<Pair<String, String>>? = null,
    /** 2.18.0: the price list this bus goes by (emHUB's fares) and, for IDS BBSK, the tariff units of this ride. Null: no price known. */
    val tariff: String? = null,
    val units: Int? = null
)

data class TimetableConnection(
    val id: String,
    val departureTime: String,
    val arrivalTime: String,
    val totalDuration: String,
    val segments: List<TimetableSegment>,
    /** Minutes on foot within the connection (to another stop, between changes): fewer first when it rains. */
    val walkMinutes: Int = 0
) {
    val isDirect: Boolean
        get() = segments.size <= 1

    /** Sorts connections by date and time. */
    val departureSortKey: String
        get() = segments.firstOrNull()?.let { "${it.serviceDate} ${it.departureTime.padStart(5, '0')}" }.orEmpty()

    /** When the last bus gets there, in epoch minutes (Slovak time); null when unknown. */
    val arrivalMinute: Long?
        get() = segments.lastOrNull()?.let { s ->
            val day = runCatching { java.time.LocalDate.parse(s.serviceDate) }.getOrNull() ?: return null
            val leaves = parseClock(s.departureTime) ?: return null
            val arrives = parseClock(s.arrivalTime) ?: return null
            day.plusDays(if (arrives < leaves) 1 else 0).atTime(arrives).atZone(SK_ZONE).toEpochSecond() / 60
        }
}

/**
 * When it rains: connections with little walking first, but never ahead of one that gets there more than
 * [RAIN_WALK_PENALTY_MIN] sooner (EM-0081: a 7:32 bus sank below an 8:50 one for a 6-minute walk).
 */
const val RAIN_WALK_PENALTY_MIN = 15L

fun rainOrder(connections: List<TimetableConnection>): List<TimetableConnection> =
    connections.sortedBy { c -> (c.arrivalMinute ?: Long.MAX_VALUE / 2) + if (c.walkMinutes > 3) RAIN_WALK_PENALTY_MIN else 0 }

data class TimetablePagingCursor(
    val citySlug: String,
    val routePrefix: String,
    val fromText: String,
    val toText: String,
    val handle: String,
    val searchDate: String,
    val listedIds: List<String>,
    val allowNext: Boolean = true
)

data class TimetableSearchResult(
    val connections: List<TimetableConnection>,
    val pagingCursor: TimetablePagingCursor?,
    /** 2.17.0: found in the timetables kept on the phone (emHUB unreachable): by the timetable only. */
    val offline: Boolean = false
)

data class TimetableState(
    val isLoading: Boolean = false,
    /** Planner scope; one timetable for all buses now, kept for saved routes. */
    val citySlug: String = PLANNER_ALL,
    val fromInput: String = "",
    val toInput: String = "",
    val timeInput: String = "",
    /** ISO date to search on, "" = today. */
    val dateInput: String = "",
    val directOnly: Boolean = false,
    /** EM-0053: the time is when to be there, not when to leave. */
    val arriveBy: Boolean = false,
    /** 2.17.0: rain expected where the journey starts: connections with less walking come first. */
    val rainy: Boolean = false,
    /** 2.17.0: these results are from the offline timetables. */
    val offline: Boolean = false,
    val fromSuggestions: List<CpStopSuggestion> = emptyList(),
    val toSuggestions: List<CpStopSuggestion> = emptyList(),
    val selectedFromSuggestion: CpStopSuggestion? = null,
    val selectedToSuggestion: CpStopSuggestion? = null,
    val isLoadingFromSuggestions: Boolean = false,
    val isLoadingToSuggestions: Boolean = false,
    val isLoadingMore: Boolean = false,
    val canLoadMore: Boolean = false,
    val pagingCursor: TimetablePagingCursor? = null,
    val connections: List<TimetableConnection> = emptyList(),
    val errorMessage: String = "",
    val savedRoutes: List<SavedRoute> = emptyList(),
    val recentRoutes: List<SavedRoute> = emptyList(),
    /** Starred connections, shown on the home tab. */
    val savedJourneys: List<SavedJourney> = emptyList(),
    val rides: List<RecurringRide> = emptyList()
)

/** A from/to pair the user saved or searched recently. */
data class SavedRoute(
    val citySlug: String,
    val fromText: String,
    val toText: String,
    val from: CpStopSuggestion? = null,
    val to: CpStopSuggestion? = null
) {
    fun sameAs(other: SavedRoute): Boolean =
        fromText.equals(other.fromText, ignoreCase = true) && toText.equals(other.toText, ignoreCase = true)
}
