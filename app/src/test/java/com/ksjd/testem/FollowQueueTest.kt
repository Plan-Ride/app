package com.ksjd.testem

import com.ksjd.testem.live.TrackedTrip
import com.ksjd.testem.live.TripRef
import com.ksjd.testem.live.TripTracking
import org.junit.Assert.assertEquals
import org.junit.Test

/** EM-0100: several followed buses; the one leaving first is the live one. */
class FollowQueueTest {
    private fun bus(line: String, leavesMs: Long) = TrackedTrip(TripRef(line, 0, "", 0, "X"), emptyList(), departsAtMs = leavesMs)

    @Test fun soonestIsFirstAndEachBusOnce() {
        val now = 1_000_000_000L
        val live = bus("1", now + 20 * 60_000L)
        val sooner = bus("2", now + 5 * 60_000L)
        val again = live.copy(alightOrder = 3)
        assertEquals(listOf("2", "1"), TripTracking.soonestFirst(listOf(sooner, live)).map { it.ref.line })
        // Followed again with another stop to get off at: the new one counts, still once.
        assertEquals(listOf(again), TripTracking.soonestFirst(listOf(again, live)))
        // Time not known (0): counts as leaving now, so it stays the live one.
        assertEquals("9", TripTracking.soonestFirst(listOf(sooner, bus("9", 0))).first().ref.line)
    }

    @Test fun busesLongGoneAreForgotten() {
        val now = 1_000_000_000L
        val waiting = listOf(bus("1", now - 11 * 60_000L), bus("2", now - 5 * 60_000L), bus("3", now + 60_000L))
        assertEquals(listOf("2", "3"), TripTracking.stillAhead(waiting, now).map { it.ref.line })
    }
}

/** 2.18.0: a connection search answered again from the phone. */
class SearchCacheTest {
    private fun at(clock: String) = java.time.LocalDate.parse("2026-11-10").atTime(parseClock(clock)!!).atZone(SK_ZONE).toEpochSecond()
    private fun leaving(clock: String) = TimetableConnection(clock, clock, "", "", listOf(
        TimetableSegment(line = "Bus 1", operatorName = "", departureTime = clock, departureStop = "A", arrivalTime = clock, arrivalStop = "B",
            departurePlatform = "", arrivalPlatform = "", routeUrl = "", serviceDate = "2026-11-10")))
    private val found = TimetableSearchResult(listOf("10:02", "10:10", "10:20", "10:30").map(::leaving), null)

    @Test fun sameTimeIsReusedWhole() = assertEquals(found, TimetableService.reuse(found, at("10:00"), at("10:00"), false))

    @Test fun laterDropsBusesGone() =
        assertEquals(listOf("10:10", "10:20", "10:30"), TimetableService.reuse(found, at("10:00"), at("10:05"), false)?.connections?.map { it.departureTime })

    @Test fun askedAgainWhenTooFewOrOtherTime() {
        assertEquals(null, TimetableService.reuse(found, at("10:00"), at("10:11"), false))  // 2 left, and past 10 min
        assertEquals(null, TimetableService.reuse(found, at("10:00"), at("9:55"), false))
        assertEquals(null, TimetableService.reuse(found, at("10:00"), at("10:05"), true))
    }

    @Test fun oldAnswerKeepsWhatStillLeaves() =
        assertEquals(listOf("10:20", "10:30"), TimetableService.stillLeaving(found, at("10:15")).connections.map { it.departureTime })

    @Test fun guessesAreTheRidersOwnTripsFromThere() {
        val routes = listOf(SavedRoute(PLANNER_ALL, "A", "B"), SavedRoute(PLANNER_ALL, "X", "Y"), SavedRoute(PLANNER_ALL, "a", "b"),
            SavedRoute("slovensko", "A", "Old"), SavedRoute(PLANNER_ALL, "A", "C"), SavedRoute(PLANNER_ALL, "A", "D"))
        assertEquals(listOf("B", "C"), AppViewModel.guesses("A", routes).map { it.toText })
    }
}
